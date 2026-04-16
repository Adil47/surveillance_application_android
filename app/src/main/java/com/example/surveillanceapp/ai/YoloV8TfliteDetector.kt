package com.example.surveillanceapp.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 TFLite inference for person-only detection.
 *
 * Expected model: input 640x640x3 float32, output shaped like [1,84,8400] or [1,8400,84]
 * (batch may be present as leading 1).
 */
class YoloV8TfliteDetector(
    context: Context,
    modelAssetPath: String = MODEL_ASSET_NAME,
    private val defaultConfThreshold: Float = 0.32f,
    private val iouThreshold: Float = 0.45f,
) : AutoCloseable {

    private val gpuDelegate: GpuDelegate?
    private val interpreter: Interpreter
    private val backendName: String

    private val inputW = 640
    private val inputH = 640
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * inputW * inputH * 3 * 4).order(ByteOrder.nativeOrder())

    @Volatile
    private var loggedOutputShape = false

    init {
        val model = loadModelFile(context, modelAssetPath)
        var createdInterpreter: Interpreter? = null
        var createdDelegate: GpuDelegate? = null
        var createdBackend = "CPU/XNNPACK"

        // 1) Try GPU delegate first.
        runCatching {
            val gpu = GpuDelegate()
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
                addDelegate(gpu)
            }
            val interp = Interpreter(model, options)
            createdDelegate = gpu
            createdInterpreter = interp
            createdBackend = "GPU delegate"
        }.onFailure {
            runCatching { createdDelegate?.close() }
            createdDelegate = null
        }

        // 2) Final safe fallback: CPU/XNNPACK only.
        if (createdInterpreter == null) {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            createdInterpreter = Interpreter(model, options)
            createdBackend = "CPU/XNNPACK"
        }

        gpuDelegate = createdDelegate
        backendName = createdBackend
        interpreter = createdInterpreter
            ?: throw IllegalStateException("Failed to create TFLite interpreter with all backends")
        Log.i(TAG, "Interpreter backend selected: $backendName")
        logModelTensorMetadata()
    }

    data class InferenceMetrics(
        val rawPersonOverLoose: Int,
        val rawAnyClassOverLoose: Int,
        val rawPersonOverThreshold: Int,
        val strongestClassIndex: Int,
        val strongestClassScore: Float,
        val outputShapeLabel: String,
    )

    private data class OutputSpec(
        val tensorIndex: Int,
        val tensorName: String,
        val rawShape: IntArray,
        val squeezedShape: IntArray,
        val dataType: DataType,
        val quantScale: Float,
        val quantZeroPoint: Int,
    )

    fun detect(frameBitmap: Bitmap, confThreshold: Float = defaultConfThreshold): Pair<List<DetectionResult>, InferenceMetrics> {
        if (interpreter.getInputTensor(0).dataType() != DataType.FLOAT32) {
            return emptyList<DetectionResult>() to InferenceMetrics(0, 0, 0, -1, 0f, "non-float32 input")
        }

        val prep = preprocess(frameBitmap, inputW, inputH)
        val outputSpec = pickOutputSpec()
        val outputShape = outputSpec.squeezedShape
        if (!loggedOutputShape) {
            loggedOutputShape = true
            Log.d(
                TAG,
                "Selected output tensor: name=${outputSpec.tensorName} | index=${outputSpec.tensorIndex} | " +
                    "rawShape=${outputSpec.rawShape.contentToString()} | " +
                    "squeezedShape=${outputShape.contentToString()} | type=${outputSpec.dataType}",
            )
        }

        val output = runInference(outputSpec)

        val (preNms, postMetrics) = postprocess(
            output = output,
            outputShape = outputShape,
            originalWidth = frameBitmap.width,
            originalHeight = frameBitmap.height,
            scale = prep.scale,
            padX = prep.padX,
            padY = prep.padY,
            confThreshold = confThreshold,
        )
        val afterNms = if (preNms.isEmpty()) preNms else nms(preNms, iouThreshold)
        val shapeLabel =
            "$backendName | ${outputSpec.tensorName}@${outputSpec.tensorIndex} ${outputSpec.dataType} " +
                "raw=${outputSpec.rawShape.contentToString()} squeezed=${outputShape.contentToString()}"
        return afterNms to postMetrics.copy(outputShapeLabel = shapeLabel)
    }

    private fun logModelTensorMetadata() {
        val inputCount = interpreter.inputTensorCount
        for (i in 0 until inputCount) {
            val t = interpreter.getInputTensor(i)
            val q = t.quantizationParams()
            Log.i(
                TAG,
                "Input[$i]: name=${t.name()} shape=${t.shape().contentToString()} type=${t.dataType()} " +
                    "quant(scale=${q.scale}, zeroPoint=${q.zeroPoint})",
            )
        }

        val outputCount = interpreter.outputTensorCount
        for (i in 0 until outputCount) {
            val t = interpreter.getOutputTensor(i)
            val q = t.quantizationParams()
            Log.i(
                TAG,
                "Output[$i]: name=${t.name()} shape=${t.shape().contentToString()} type=${t.dataType()} " +
                    "quant(scale=${q.scale}, zeroPoint=${q.zeroPoint})",
            )
        }
    }

    private fun pickOutputSpec(): OutputSpec {
        val attrs = 84
        val count = interpreter.outputTensorCount
        var fallback: OutputSpec? = null
        for (i in 0 until count) {
            val tensor = interpreter.getOutputTensor(i)
            val rawShape = tensor.shape()
            val squeezed = squeezeBatchIfPresent(rawShape)
            val quant = tensor.quantizationParams()
            val spec = OutputSpec(
                tensorIndex = i,
                tensorName = tensor.name(),
                rawShape = rawShape,
                squeezedShape = squeezed,
                dataType = tensor.dataType(),
                quantScale = quant.scale,
                quantZeroPoint = quant.zeroPoint,
            )
            if (fallback == null) fallback = spec
            if (squeezed.size == 3 && (squeezed[1] == attrs || squeezed[2] == attrs)) {
                return spec
            }
        }
        return fallback ?: throw IllegalStateException("Model has no output tensor")
    }

    private fun runInference(spec: OutputSpec): FloatArray {
        val elementCount = spec.squeezedShape.reduce { a, b -> a * b }
        return when (spec.dataType) {
            DataType.FLOAT32 -> {
                // Use a direct ByteBuffer for outputs so TensorFlow Lite does not enforce
                // Java array rank matching (e.g. [1,84,8400] vs flat [705600]).
                val bytes = ByteBuffer
                    .allocateDirect(elementCount * 4)
                    .order(ByteOrder.nativeOrder())
                runModel(spec, bytes)
                bytes.rewind()
                val out = FloatArray(elementCount)
                bytes.asFloatBuffer().get(out)
                out
            }

            DataType.UINT8, DataType.INT8 -> {
                val bytes = ByteBuffer.allocateDirect(elementCount).order(ByteOrder.nativeOrder())
                runModel(spec, bytes)
                bytes.rewind()
                val raw = ByteArray(elementCount)
                bytes.get(raw)
                val scale = if (spec.quantScale == 0f) 1f else spec.quantScale
                val zero = spec.quantZeroPoint
                FloatArray(elementCount) { idx ->
                    val q = if (spec.dataType == DataType.UINT8) {
                        raw[idx].toInt() and 0xFF
                    } else {
                        raw[idx].toInt()
                    }
                    (q - zero) * scale
                }
            }

            else -> throw IllegalStateException("Unsupported output tensor type: ${spec.dataType}")
        }
    }

    private fun runModel(spec: OutputSpec, outputBuffer: Any) {
        try {
            if (interpreter.outputTensorCount == 1 && spec.tensorIndex == 0) {
                interpreter.run(inputBuffer, outputBuffer)
                return
            }
            val outputs = HashMap<Int, Any>(1)
            outputs[spec.tensorIndex] = outputBuffer
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "Output read failed for tensor '${spec.tensorName}' idx=${spec.tensorIndex} " +
                    "type=${spec.dataType} rawShape=${spec.rawShape.contentToString()}: ${t.message}",
                t,
            )
        }
    }

    private fun squeezeBatchIfPresent(shape: IntArray): IntArray {
        if (shape.size == 4 && shape[0] == 1) {
            return intArrayOf(shape[1], shape[2], shape[3])
        }
        return shape
    }

    private data class PrepMeta(val scale: Float, val padX: Float, val padY: Float)

    private fun preprocess(src: Bitmap, dstW: Int, dstH: Int): PrepMeta {
        inputBuffer.rewind()

        val scale = min(dstW.toFloat() / src.width.toFloat(), dstH.toFloat() / src.height.toFloat())
        val scaledW = max(1, (src.width * scale).toInt())
        val scaledH = max(1, (src.height * scale).toInt())
        val padX = (dstW - scaledW) / 2f
        val padY = (dstH - scaledH) / 2f

        val resized = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val pixels = IntArray(scaledW * scaledH)
        resized.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)

        val padXi = padX.toInt()
        val padYi = padY.toInt()
        val innerRight = padXi + scaledW
        val innerBottom = padYi + scaledH

        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val inside = x >= padXi && x < innerRight && y >= padYi && y < innerBottom
                if (!inside) {
                    inputBuffer.putFloat(114f / 255f)
                    inputBuffer.putFloat(114f / 255f)
                    inputBuffer.putFloat(114f / 255f)
                } else {
                    val sx = (x - padXi).coerceIn(0, scaledW - 1)
                    val sy = (y - padYi).coerceIn(0, scaledH - 1)
                    val pixel = pixels[sy * scaledW + sx]
                    val r = ((pixel shr 16) and 0xFF) / 255f
                    val g = ((pixel shr 8) and 0xFF) / 255f
                    val b = (pixel and 0xFF) / 255f
                    inputBuffer.putFloat(r)
                    inputBuffer.putFloat(g)
                    inputBuffer.putFloat(b)
                }
            }
        }

        if (resized !== src) resized.recycle()
        return PrepMeta(scale = scale, padX = padX, padY = padY)
    }

    private fun postprocess(
        output: FloatArray,
        outputShape: IntArray,
        originalWidth: Int,
        originalHeight: Int,
        scale: Float,
        padX: Float,
        padY: Float,
        confThreshold: Float,
    ): Pair<ArrayList<DetectionResult>, InferenceMetrics> {
        if (outputShape.size != 3) {
            return ArrayList<DetectionResult>(0) to InferenceMetrics(0, 0, 0, -1, 0f, outputShape.contentToString())
        }

        val dim1 = outputShape[1]
        val dim2 = outputShape[2]
        val classCount = 80
        val attrs = 4 + classCount

        val byChannelFirst = dim1 == attrs
        val candidates: Int
        val strideAttr: Int
        val strideCand: Int
        if (byChannelFirst) {
            // [1, 84, N] — attribute-major
            candidates = dim2
            strideAttr = dim2
            strideCand = 1
        } else if (dim2 == attrs) {
            // [1, N, 84] — anchor-major (this layout was previously indexed incorrectly)
            candidates = dim1
            strideAttr = 1
            strideCand = dim2
        } else {
            Log.w(
                TAG,
                "Unexpected YOLO shape [1,$dim1,$dim2]; expected attrs=$attrs on one axis",
            )
            return ArrayList<DetectionResult>(0) to InferenceMetrics(0, 0, 0, -1, 0f, outputShape.contentToString())
        }

        fun at(attr: Int, cand: Int): Float = output[attr * strideAttr + cand * strideCand]

        val boxes = ArrayList<DetectionResult>(64)
        var looseCount = 0
        var anyClassLooseCount = 0
        var overThreshCount = 0
        val looseFloor = 0.01f
        var strongestClassIndex = -1
        var strongestClassScore = 0f

        for (i in 0 until candidates) {
            val cx = at(0, i)
            val cy = at(1, i)
            val w = at(2, i)
            val h = at(3, i)
            var bestClassIdx = -1
            var bestClassScore = 0f
            for (c in 0 until classCount) {
                val score = toClassProbability(at(4 + c, i))
                if (score > bestClassScore || bestClassIdx == -1) {
                    bestClassScore = score
                    bestClassIdx = c
                }
            }
            if (bestClassScore >= looseFloor) anyClassLooseCount++
            if (bestClassScore > strongestClassScore || strongestClassIndex == -1) {
                strongestClassScore = bestClassScore
                strongestClassIndex = bestClassIdx
            }

            // Strict person detection: COCO class 0, and it must also be the top class for that anchor.
            val personProb = toClassProbability(at(4 + PERSON_CLASS_INDEX, i))
            if (personProb >= looseFloor) looseCount++
            if (personProb < confThreshold) continue
            if (bestClassIdx != PERSON_CLASS_INDEX) continue
            if (bestClassScore < confThreshold) continue
            overThreshCount++

            // Some exports emit normalized coords, others emit input-pixel coords.
            // Also, some exports expose [x1,y1,x2,y2] instead of [cx,cy,w,h].
            // Try robust decode candidates and keep the first plausible one.
            val decoded = decodeBoxInputSpace(cx, cy, w, h) ?: continue
            val x1 = decoded.left
            val y1 = decoded.top
            val x2 = decoded.right
            val y2 = decoded.bottom

            // Drop noise boxes: too tiny, or covering almost the whole frame.
            val boxW = x2 - x1
            val boxH = y2 - y1
            if (boxW <= MIN_BOX_SIDE_PX || boxH <= MIN_BOX_SIDE_PX) continue
            val areaFrac = (boxW * boxH) / (inputW * inputH).toFloat()
            if (areaFrac > MAX_BOX_AREA_FRAC) continue

            val ox1 = ((x1 - padX) / scale).coerceIn(0f, originalWidth.toFloat())
            val oy1 = ((y1 - padY) / scale).coerceIn(0f, originalHeight.toFloat())
            val ox2 = ((x2 - padX) / scale).coerceIn(0f, originalWidth.toFloat())
            val oy2 = ((y2 - padY) / scale).coerceIn(0f, originalHeight.toFloat())

            if (ox2 <= ox1 || oy2 <= oy1) continue
            boxes += DetectionResult(
                boundingBox = RectF(ox1, oy1, ox2, oy2),
                confidence = personProb,
                label = PERSON_LABEL,
            )
        }

        val shapeLabel = outputShape.contentToString()
        return boxes to InferenceMetrics(
            rawPersonOverLoose = looseCount,
            rawAnyClassOverLoose = anyClassLooseCount,
            rawPersonOverThreshold = overThreshCount,
            strongestClassIndex = strongestClassIndex,
            strongestClassScore = strongestClassScore,
            outputShapeLabel = shapeLabel,
        )
    }

    /**
     * Ultralytics TFLite graphs usually emit sigmoid probabilities; raw logits need [0,1] mapping.
     */
    private fun toClassProbability(raw: Float): Float {
        if (raw in 0f..1f) return raw
        val x = raw.coerceIn(-80f, 80f)
        return (1f / (1f + exp(-x))).coerceIn(0f, 1f)
    }

    private fun decodeBoxInputSpace(a: Float, b: Float, c: Float, d: Float): RectF? {
        val maxCoord = max(max(a, b), max(c, d))
        val normalized = maxCoord <= 2f
        val sx = if (normalized) inputW.toFloat() else 1f
        val sy = if (normalized) inputH.toFloat() else 1f

        val candidates = arrayOf(
            // [cx, cy, w, h]
            RectF((a - c / 2f) * sx, (b - d / 2f) * sy, (a + c / 2f) * sx, (b + d / 2f) * sy),
            // [x1, y1, x2, y2]
            RectF(a * sx, b * sy, c * sx, d * sy),
        )

        for (r in candidates) {
            val l = min(r.left, r.right)
            val t = min(r.top, r.bottom)
            val rr = max(r.left, r.right)
            val bb = max(r.top, r.bottom)
            val w = rr - l
            val h = bb - t
            if (w <= 0f || h <= 0f) continue
            if (w > inputW * 1.1f || h > inputH * 1.1f) continue
            return RectF(l, t, rr, bb)
        }
        return null
    }

    private fun nms(input: List<DetectionResult>, iouThr: Float): List<DetectionResult> {
        val sorted = input.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<DetectionResult>(sorted.size)
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best
            sorted.removeAll { other -> iou(best.boundingBox, other.boundingBox) > iouThr }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interL = max(a.left, b.left)
        val interT = max(a.top, b.top)
        val interR = min(a.right, b.right)
        val interB = min(a.bottom, b.bottom)
        val interW = max(0f, interR - interL)
        val interH = max(0f, interB - interT)
        val inter = interW * interH
        if (inter <= 0f) return 0f
        val ua = (a.width() * a.height()) + (b.width() * b.height()) - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    private fun loadModelFile(context: Context, assetPath: String): ByteBuffer {
        context.assets.openFd(assetPath).use { afd ->
            afd.createInputStream().channel.use { channel ->
                return channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
    }

    companion object {
        const val MODEL_ASSET_NAME = "yolov8n_float32.tflite"
        const val PERSON_LABEL = "Person"
        private const val PERSON_CLASS_INDEX = 0
        private const val MIN_BOX_SIDE_PX = 2f
        private const val MAX_BOX_AREA_FRAC = 0.98f
        private const val TAG = "YoloV8Tflite"
    }
}
