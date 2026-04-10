package com.example.surveillanceapp.behavior

import android.graphics.PointF
import android.graphics.RectF
import com.example.surveillanceapp.ai.DetectionResult
import kotlin.math.hypot

/**
 * Lightweight centroid tracker for person detections.
 */
class SimplePersonTracker(
    private val maxMatchDistancePx: Float = 120f,
    private val maxLostMs: Long = 2_000L,
) {
    private data class MutableTrack(
        val id: Long,
        var bbox: RectF,
        var centroid: PointF,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var confidence: Float,
    )

    private var nextId = 1L
    private val tracks = LinkedHashMap<Long, MutableTrack>()

    fun update(detections: List<DetectionResult>, nowMs: Long): List<TrackedPerson> {
        removeExpired(nowMs)
        val remainingIds = tracks.keys.toMutableSet()

        for (det in detections) {
            val c = det.boundingBox.center()
            val matchId = remainingIds
                .mapNotNull { id ->
                    val t = tracks[id] ?: return@mapNotNull null
                    val d = distancePx(c, t.centroid)
                    if (d <= maxMatchDistancePx) id to d else null
                }
                .minByOrNull { it.second }
                ?.first

            if (matchId == null) {
                val id = nextId++
                tracks[id] = MutableTrack(
                    id = id,
                    bbox = RectF(det.boundingBox),
                    centroid = c,
                    firstSeenMs = nowMs,
                    lastSeenMs = nowMs,
                    confidence = det.confidence,
                )
            } else {
                val t = tracks[matchId] ?: continue
                t.bbox = RectF(det.boundingBox)
                t.centroid = c
                t.lastSeenMs = nowMs
                t.confidence = det.confidence
                remainingIds.remove(matchId)
            }
        }

        return tracks.values.map {
            TrackedPerson(
                trackId = it.id,
                bbox = RectF(it.bbox),
                centroid = PointF(it.centroid.x, it.centroid.y),
                firstSeenMs = it.firstSeenMs,
                lastSeenMs = it.lastSeenMs,
                confidence = it.confidence,
            )
        }
    }

    private fun removeExpired(nowMs: Long) {
        tracks.entries.removeAll { (_, t) -> nowMs - t.lastSeenMs > maxLostMs }
    }

    private fun RectF.center(): PointF = PointF(centerX(), centerY())

    private fun distancePx(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}
