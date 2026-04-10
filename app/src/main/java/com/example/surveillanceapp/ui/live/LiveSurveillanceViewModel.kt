package com.example.surveillanceapp.ui.live

import android.app.Application
import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveillanceapp.ai.DetectionResult
import com.example.surveillanceapp.ai.DetectorStatus
import com.example.surveillanceapp.ai.YoloV8TfliteDetector
import com.example.surveillanceapp.behavior.AlertEvent
import com.example.surveillanceapp.behavior.AlertType
import com.example.surveillanceapp.behavior.CrowdDetector
import com.example.surveillanceapp.behavior.LoiteringDetector
import com.example.surveillanceapp.behavior.RestrictedAreaDetector
import com.example.surveillanceapp.behavior.SimplePersonTracker
import com.example.surveillanceapp.dji.DroneFrameConverter
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import android.graphics.PointF

/**
 * Binds DJI-decoded live video to a [Surface] and runs YOLOv8 TFLite person detection on frames.
 *
 * Notes:
 * - Default decode camera index is [ComponentIndexType.LEFT_OR_MAIN] (main/zoom gimbal on most airframes).
 * - [ICameraStreamManager.CameraFrameListener] may reuse backing arrays; we copy each frame before decoding.
 */
class LiveSurveillanceViewModel(app: Application) : AndroidViewModel(app) {

    data class FrameStats(
        val received: Long = 0L,
        val lastWidth: Int = 0,
        val lastHeight: Int = 0,
        val declaredCameras: String = "",
    )

    private data class RawFrame(
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val width: Int,
        val height: Int,
        val format: ICameraStreamManager.FrameFormat,
    )

    private val cameraStream = MediaDataCenter.getInstance().cameraStreamManager
    private val frameCounter = AtomicLong(0)
    private val droneInputCounter = AtomicLong(0)

    private val decodeChannel = Channel<RawFrame>(Channel.CONFLATED)

    private val statsMutex = Mutex()
    private val _stats = MutableStateFlow(FrameStats())
    val stats: StateFlow<FrameStats> = _stats.asStateFlow()

    private val _lastPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val lastPreviewBitmap: StateFlow<Bitmap?> = _lastPreviewBitmap.asStateFlow()
    private val _detections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val detections: StateFlow<List<DetectionResult>> = _detections.asStateFlow()
    private val _detectorStatus = MutableStateFlow(DetectorStatus())
    val detectorStatus: StateFlow<DetectorStatus> = _detectorStatus.asStateFlow()
    private val _alertHistory = MutableStateFlow<List<AlertEvent>>(emptyList())
    val alertHistory: StateFlow<List<AlertEvent>> = _alertHistory.asStateFlow()
    private val _activeAlertTypes = MutableStateFlow<Set<AlertType>>(emptySet())
    val activeAlertTypes: StateFlow<Set<AlertType>> = _activeAlertTypes.asStateFlow()
    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()
    private val _zoneEditorEnabled = MutableStateFlow(false)
    val zoneEditorEnabled: StateFlow<Boolean> = _zoneEditorEnabled.asStateFlow()
    private val _restrictedZoneNormalized = MutableStateFlow(defaultZoneNormalized())
    val restrictedZoneNormalized: StateFlow<List<Pair<Float, Float>>> = _restrictedZoneNormalized.asStateFlow()

    @Volatile
    private var streaming = false

    private val primaryCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    private var detector: YoloV8TfliteDetector? = null
    private val tracker = SimplePersonTracker()
    private val loiteringDetector = LoiteringDetector(loiterDurationMs = 10_000L)
    private val restrictedAreaDetector = RestrictedAreaDetector(
        zones = listOf(
            // Default demo zone near center of frame (pixel coordinates in source frame space).
            listOf(
                PointF(220f, 180f),
                PointF(420f, 180f),
                PointF(420f, 420f),
                PointF(220f, 420f),
            ),
        ),
    )
    private val crowdDetector = CrowdDetector(threshold = 5)
    private val demoFrameCounter = AtomicLong(0)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                detector = YoloV8TfliteDetector(getApplication())
                _detectorStatus.value = _detectorStatus.value.copy(ready = true, error = null)
            }.onFailure { e ->
                _detectorStatus.value = _detectorStatus.value.copy(
                    ready = false,
                    error = e.message ?: "Unable to initialize TFLite detector.",
                )
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.Default) {
            for (frame in decodeChannel) {
                val bmp = DroneFrameConverter.toBitmap(
                    frame.data,
                    frame.offset,
                    frame.length,
                    frame.width,
                    frame.height,
                    frame.format,
                )
                if (bmp != null) processBitmap(bmp)
            }
        }
    }

    fun processDemoFrame(bitmap: Bitmap) {
        _isDemoMode.value = true
        // Step 7: process every Nth frame to keep latency low on-device.
        val n = demoFrameCounter.incrementAndGet()
        if (n % DEMO_PROCESS_EVERY_NTH != 0L) {
            bitmap.recycle()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            processBitmap(bitmap)
        }
    }

    private suspend fun processBitmap(bmp: Bitmap) {
        updateRestrictedZoneForCurrentFrame(bmp.width, bmp.height)
        val startNs = System.nanoTime()
        val detectorLocal = detector
        val detections = if (detectorLocal != null) {
            runCatching { detectorLocal.detect(bmp) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val ms = (System.nanoTime() - startNs) / 1_000_000L
        val nowMs = System.currentTimeMillis()
        val tracks = tracker.update(detections, nowMs)
        val alerts = buildList {
            addAll(loiteringDetector.detect(tracks, nowMs))
            addAll(restrictedAreaDetector.detect(tracks, nowMs))
            addAll(crowdDetector.detect(tracks, nowMs))
        }

        withContext(Dispatchers.Main.immediate) {
            val old = _lastPreviewBitmap.value
            _lastPreviewBitmap.value = bmp
            old?.recycle()
            _detections.value = detections
            val count = frameCounter.incrementAndGet()
            _stats.value = _stats.value.copy(
                received = count,
                lastWidth = bmp.width,
                lastHeight = bmp.height,
            )
            _detectorStatus.value = _detectorStatus.value.copy(
                inferenceMs = ms,
                detections = detections.size,
            )
            if (alerts.isNotEmpty()) {
                val merged = (alerts + _alertHistory.value).take(30)
                _alertHistory.value = merged
                _activeAlertTypes.value = alerts.map { it.type }.toSet()
            } else {
                _activeAlertTypes.value = emptySet()
            }
        }
    }

    private val availableCameraListener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
        override fun onAvailableCameraUpdated(availableCameraList: MutableList<ComponentIndexType>) {
            val label = availableCameraList.joinToString { it.name }
            viewModelScope.launch {
                statsMutex.withLock {
                    _stats.value = _stats.value.copy(declaredCameras = label)
                }
            }
        }

        override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: MutableMap<ComponentIndexType, Boolean>) {
            // Step 6 can reflect per-lens stream enablement in the UI.
        }
    }

    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat,
        ) {
            // Step 7: process every Nth drone frame for stable FPS/latency.
            val idx = droneInputCounter.incrementAndGet()
            if (idx % DRONE_PROCESS_EVERY_NTH != 0L) return

            val n = frameCounter.incrementAndGet()

            if (n == 1L || n % 12 == 0L) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    statsMutex.withLock {
                        _stats.value = _stats.value.copy(
                            received = n,
                            lastWidth = width,
                            lastHeight = height,
                        )
                    }
                }
            }

            if (length <= 0 || offset < 0 || offset + length > frameData.size) return
            val copy = ByteArray(length)
            System.arraycopy(frameData, offset, copy, 0, length)
            decodeChannel.trySend(
                RawFrame(
                    data = copy,
                    offset = 0,
                    length = length,
                    width = width,
                    height = height,
                    format = format,
                ),
            )
        }
    }

    fun startVideoPipeline() {
        if (streaming) return
        streaming = true
        _isDemoMode.value = false

        runCatching {
            cameraStream.setKeepAliveDecoding(true)
            cameraStream.addAvailableCameraUpdatedListener(availableCameraListener)
            cameraStream.enableStream(primaryCameraIndex, true)
            cameraStream.addFrameListener(
                primaryCameraIndex,
                ICameraStreamManager.FrameFormat.RGBA_8888,
                frameListener,
            )
        }.onFailure { e ->
            _detectorStatus.value = _detectorStatus.value.copy(
                error = "Live stream standby: ${e.message ?: "no aircraft/video source yet"}",
            )
        }
    }

    fun stopVideoPipeline() {
        if (!streaming) return
        streaming = false

        runCatching { cameraStream.removeFrameListener(frameListener) }
        runCatching { cameraStream.removeAvailableCameraUpdatedListener(availableCameraListener) }
        runCatching { cameraStream.enableStream(primaryCameraIndex, false) }
        runCatching { cameraStream.setKeepAliveDecoding(false) }

        val old = _lastPreviewBitmap.value
        _lastPreviewBitmap.value = null
        old?.recycle()
        _detections.value = emptyList()
        _activeAlertTypes.value = emptySet()
        _detectorStatus.value = _detectorStatus.value.copy(error = null)
    }

    fun enableZoneEditor(enabled: Boolean) {
        _zoneEditorEnabled.value = enabled
    }

    fun onZoneTap(nx: Float, ny: Float) {
        if (!_zoneEditorEnabled.value) return
        val current = _restrictedZoneNormalized.value.toMutableList()
        if (current.size >= 4) current.clear()
        current += nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
        _restrictedZoneNormalized.value = current
    }

    fun onZoneDrag(index: Int, nx: Float, ny: Float) {
        if (!_zoneEditorEnabled.value) return
        val current = _restrictedZoneNormalized.value.toMutableList()
        if (index !in current.indices) return
        current[index] = nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
        _restrictedZoneNormalized.value = current
    }

    fun clearZone() {
        _restrictedZoneNormalized.value = emptyList()
    }

    fun completeZone() {
        _zoneEditorEnabled.value = false
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        cameraStream.putCameraStreamSurface(
            primaryCameraIndex,
            surface,
            width,
            height,
            ICameraStreamManager.ScaleType.CENTER_INSIDE,
        )
    }

    fun detachSurface(surface: Surface) {
        runCatching { cameraStream.removeCameraStreamSurface(surface) }
    }

    override fun onCleared() {
        stopVideoPipeline()
        runCatching { detector?.close() }
        detector = null
        super.onCleared()
    }

    private fun updateRestrictedZoneForCurrentFrame(width: Int, height: Int) {
        val normalized = _restrictedZoneNormalized.value
        val zonePx = normalized.map { p ->
            PointF(p.first * width.toFloat(), p.second * height.toFloat())
        }
        restrictedAreaDetector.setZones(if (zonePx.size >= 3) listOf(zonePx) else emptyList())
    }

    private companion object {
        const val DRONE_PROCESS_EVERY_NTH = 2L
        const val DEMO_PROCESS_EVERY_NTH = 3L

        fun defaultZoneNormalized(): List<Pair<Float, Float>> = listOf(
            0.34f to 0.28f,
            0.66f to 0.28f,
            0.66f to 0.66f,
            0.34f to 0.66f,
        )
    }
}
