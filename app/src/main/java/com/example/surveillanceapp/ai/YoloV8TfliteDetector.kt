package com.example.surveillanceapp.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 TFLite inference for person-only detection.
 *
 * Expected model: input 640x640x3 float32, output shaped like [1,84,8400] or [1,8400,84].
 */
class YoloV8TfliteDetector(
    context: Context,
    modelAssetPath: String = MODEL_ASSET_NAME,
    private val confThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
) : AutoCloseable {

    private val gpuDelegate: GpuDelegate?
    private val interpreter: Interpreter

    private val inputW = 640
    private val inputH = 640
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * inputW * inputH * 3 * 4).order(ByteOrder.nativeOrder())

    init {
        val options = Interpreter.Options()
        var localDelegate: GpuDelegate? = null
        try {
            localDelegate = GpuDelegate()
            options.addDelegate(localDelegate)
        } catch (_: Throwable) {
            localDelegate = null
        }
        gpuDelegate = localDelegate
        interpreter = Interpreter(loadModelFile(context, modelAssetPath), options)
    }

    fun detect(frameBitmap: Bitmap): List<DetectionResult> {
        if (interpreter.getInputTensor(0).dataType() != DataType.FLOAT32) {
            return emptyList()
        }

        val prep = preprocess(frameBitmap, inputW, inputH)
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = FloatArray(outputShape.reduce { a, b -> a * b })
        interpreter.run(inputBuffer, output)
        return postprocess(
            output = output,
            outputShape = outputShape,
            originalWidth = frameBitmap.width,
            originalHeight = frameBitmap.height,
            scale = prep.scale,
            padX = prep.padX,
            padY = prep.padY,
        )
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

        var srcIdx = 0
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val inside = x >= padX.toInt() && x < (padX + scaledW).toInt() &&
                    y >= padY.toInt() && y < (padY + scaledH).toInt()

                if (!inside) {
                    inputBuffer.putFloat(0f)
                    inputBuffer.putFloat(0f)
                    inputBuffer.putFloat(0f)
                } else {
                    val pixel = pixels[srcIdx++]
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
    ): List<DetectionResult> {
        if (outputShape.size != 3) return emptyList()

        val first = outputShape[1]
        val second = outputShape[2]
        val classCount = 80
        val attrs = 4 + classCount
        val byAttrs = first == attrs

        val candidates = if (byAttrs) second else first
        val stride0 = if (byAttrs) second else attrs
        val stride1 = if (byAttrs) 1 else attrs
        fun at(i: Int, j: Int): Float = output[i * stride0 + j * stride1]

        val boxes = ArrayList<DetectionResult>(64)
        for (i in 0 until candidates) {
            val cx = at(0, i)
            val cy = at(1, i)
            val w = at(2, i)
            val h = at(3, i)
            val personProb = at(4, i) // class 0 -> person in COCO.
            if (personProb < confThreshold) continue

            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f

            // Undo letterbox from 640x640 preprocessing.
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

        if (boxes.isEmpty()) return boxes
        return nms(boxes, iouThreshold)
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
    }
}
