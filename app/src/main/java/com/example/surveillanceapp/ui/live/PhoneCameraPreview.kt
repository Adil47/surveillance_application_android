package com.example.surveillanceapp.ui.live

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight CameraX preview + ImageAnalysis pipeline for demo mode.
 *
 * Design choices for stability and performance:
 *  - Single [ImageAnalysis] pipeline (no snapshot fallback that competes for frames).
 *  - Hard-capped to ~[MAX_ANALYSIS_FPS] analysis frames per second at source, so heavy
 *    downstream work (YUV→Bitmap→TFLite) can never pile up even on slow devices.
 *  - Zero per-frame logging — only one log when the analyzer starts and one on errors.
 *  - Robust rebind on recomposition / reopen: [DisposableEffect] fully unbinds and the
 *    next bind re-creates preview + analysis from scratch.
 */
@Composable
fun PhoneCameraPreview(
    modifier: Modifier = Modifier,
    onFrame: (Bitmap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }
    if (!permissionGranted) return

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(previewView, lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                bindCamera(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    analysisExecutor = analysisExecutor,
                    onFrame = onFrame,
                )
            }.onFailure { Log.e(TAG, "Camera bind failed", it) }
        }, mainExecutor)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            runCatching { analysisExecutor.shutdown() }
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}

private fun bindCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    analysisExecutor: ExecutorService,
    onFrame: (Bitmap) -> Unit,
) {
    val preview = Preview.Builder().build().apply {
        surfaceProvider = previewView.surfaceProvider
    }

    val analysis = ImageAnalysis.Builder()
        .setTargetResolution(Size(640, 480))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    val minIntervalMs = 1000L / MAX_ANALYSIS_FPS
    val lastEmitMs = AtomicLong(0L)
    val rawFrameCount = AtomicLong(0L)
    val emittedFrameCount = AtomicLong(0L)

    analysis.setAnalyzer(analysisExecutor) { image ->
        try {
            val raw = rawFrameCount.incrementAndGet()
            if (raw == 1L) {
                Log.i(
                    TAG,
                    "Analyzer active: ${image.width}x${image.height}, rot=${image.imageInfo.rotationDegrees}",
                )
            }
            if (raw % 30L == 0L) {
                Log.i(TAG, "Raw frames=$raw emitted=${emittedFrameCount.get()}")
            }
            val now = System.currentTimeMillis()
            val last = lastEmitMs.get()
            if (now - last < minIntervalMs) return@setAnalyzer
            if (!lastEmitMs.compareAndSet(last, now)) return@setAnalyzer

            val bmp = imageProxyToBitmap(image) ?: return@setAnalyzer
            emittedFrameCount.incrementAndGet()
            onFrame(bmp)
        } catch (t: Throwable) {
            Log.e(TAG, "Analyzer error", t)
        } finally {
            image.close()
        }
    }

    val selector = CameraSelector.DEFAULT_BACK_CAMERA
    provider.unbindAll()
    runCatching {
        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        Log.i(TAG, "Bound preview + analysis")
    }.onFailure { err ->
        Log.w(TAG, "bind(preview+analysis) failed, falling back to preview only: ${err.message}")
        provider.unbindAll()
        runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview) }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val nv21 = yuv420888ToNv21(image) ?: return null
    val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream(64 * 1024)
    yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 75, out)
    val bytes = out.toByteArray()
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return rotateIfNeeded(decoded, image.imageInfo.rotationDegrees)
}

private fun rotateIfNeeded(src: Bitmap, rotationDegrees: Int): Bitmap {
    if (rotationDegrees % 360 == 0) return src
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    if (rotated !== src) src.recycle()
    return rotated
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray? {
    val yPlane = image.planes.getOrNull(0) ?: return null
    val uPlane = image.planes.getOrNull(1) ?: return null
    val vPlane = image.planes.getOrNull(2) ?: return null
    val ySize = image.width * image.height
    val uvSize = ySize / 2
    val out = ByteArray(ySize + uvSize)

    copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, image.width, image.height, out, 0, 1)
    // NV21 expects interleaved VU chroma.
    copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, image.width / 2, image.height / 2, out, ySize, 2)
    copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, image.width / 2, image.height / 2, out, ySize + 1, 2)
    return out
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    out: ByteArray,
    outOffset: Int,
    outPixelStride: Int,
) {
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    var outputPos = outOffset
    for (row in 0 until height) {
        val rowStart = row * rowStride
        for (col in 0 until width) {
            out[outputPos] = data[rowStart + col * pixelStride]
            outputPos += outPixelStride
        }
    }
}

private const val TAG = "PhoneCameraPreview"

/** Cap frames delivered to the detector. ~4 FPS is more than enough for demo detection
 *  and keeps CPU/memory comfortable on phones. */
private const val MAX_ANALYSIS_FPS = 4
