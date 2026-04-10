package com.example.surveillanceapp.ui.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.surveillanceapp.ai.DetectionResult
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws person detections over the live feed.
 *
 * Input coordinates are expected in source-frame pixels.
 * This view maps them using CENTER_INSIDE rules (same as video surface).
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        style = Paint.Style.FILL
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 64, 129)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val zonePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 64, 129)
        style = Paint.Style.FILL
    }
    private val zonePointLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val zonePointLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        style = Paint.Style.FILL
    }

    private var sourceW: Int = 0
    private var sourceH: Int = 0
    private var detections: List<DetectionResult> = emptyList()
    private var zoneNormalized: List<Pair<Float, Float>> = emptyList()
    private var editingZone: Boolean = false
    private var onZoneTap: ((Float, Float) -> Unit)? = null
    private var onZoneDrag: ((Int, Float, Float) -> Unit)? = null
    private var onAnyTouch: (() -> Unit)? = null
    private var draggingPointIndex: Int = -1

    fun submitDetections(sourceWidth: Int, sourceHeight: Int, values: List<DetectionResult>) {
        sourceW = sourceWidth
        sourceH = sourceHeight
        detections = values
        postInvalidateOnAnimation()
    }

    fun submitZone(
        zone: List<Pair<Float, Float>>,
        editing: Boolean,
        onTap: ((Float, Float) -> Unit)?,
        onDrag: ((Int, Float, Float) -> Unit)?,
        onTouch: (() -> Unit)?,
    ) {
        zoneNormalized = zone
        editingZone = editing
        onZoneTap = onTap
        onZoneDrag = onDrag
        onAnyTouch = onTouch
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onAnyTouch?.invoke()
        }
        if (!editingZone) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTestPoint(event.x, event.y)
                if (hit >= 0) {
                    draggingPointIndex = hit
                    return true
                }
                val nx = (event.x / width.toFloat()).coerceIn(0f, 1f)
                val ny = (event.y / height.toFloat()).coerceIn(0f, 1f)
                onZoneTap?.invoke(nx, ny)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingPointIndex >= 0) {
                    val nx = (event.x / width.toFloat()).coerceIn(0f, 1f)
                    val ny = (event.y / height.toFloat()).coerceIn(0f, 1f)
                    onZoneDrag?.invoke(draggingPointIndex, nx, ny)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingPointIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTestPoint(x: Float, y: Float): Int {
        if (zoneNormalized.isEmpty()) return -1
        val vw = width.toFloat()
        val vh = height.toFloat()
        val radius = 24f
        for ((i, p) in zoneNormalized.withIndex()) {
            val px = p.first * vw
            val py = p.second * vh
            if (hypot(x - px, y - py) <= radius) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        var dx = 0f
        var dy = 0f
        var scale = 1f
        if (sourceW > 0 && sourceH > 0) {
            // Match camera stream display scale type CENTER_INSIDE.
            scale = min(vw / sourceW.toFloat(), vh / sourceH.toFloat())
            val drawnW = sourceW * scale
            val drawnH = sourceH * scale
            dx = (vw - drawnW) / 2f
            dy = (vh - drawnH) / 2f
        }

        if (sourceW > 0 && sourceH > 0) {
            for (det in detections) {
                val r = det.boundingBox
                val mapped = RectF(
                    dx + r.left * scale,
                    dy + r.top * scale,
                    dx + r.right * scale,
                    dy + r.bottom * scale,
                )
                canvas.drawRect(mapped, boxPaint)

                val label = "${det.label} ${(det.confidence * 100f).toInt()}%"
                val tw = textPaint.measureText(label)
                val th = textPaint.textSize + 8f
                val left = mapped.left.coerceAtLeast(0f)
                val top = (mapped.top - th - 4f).coerceAtLeast(0f)
                canvas.drawRect(left, top, left + tw + 16f, top + th, textBgPaint)
                canvas.drawText(label, left + 8f, top + th - 8f, textPaint)
            }
        }

        if (zoneNormalized.isNotEmpty()) {
            val path = Path()
            zoneNormalized.forEachIndexed { i, p ->
                val x = p.first * vw
                val y = p.second * vh
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (zoneNormalized.size >= 3) path.close()
            canvas.drawPath(path, zonePaint)
            zoneNormalized.forEachIndexed { i, p ->
                val px = p.first * vw
                val py = p.second * vh
                canvas.drawCircle(px, py, 8f, zonePointPaint)
                val label = "P${i + 1}"
                val tw = zonePointLabelPaint.measureText(label)
                val left = (px + 10f).coerceAtMost(vw - tw - 12f)
                val top = (py - 26f).coerceAtLeast(0f)
                canvas.drawRect(left - 4f, top, left + tw + 4f, top + 24f, zonePointLabelBgPaint)
                canvas.drawText(label, left, top + 19f, zonePointLabelPaint)
            }
        }
    }
}
