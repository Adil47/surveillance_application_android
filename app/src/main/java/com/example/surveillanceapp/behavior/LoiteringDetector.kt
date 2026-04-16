package com.example.surveillanceapp.behavior

import android.graphics.PointF
import kotlin.math.hypot

class LoiteringDetector(
    private val loiterDurationMs: Long = 10_000L,
    private val movementRadiusPx: Float = 80f,
    private val cooldownMs: Long = 5_000L,
) {
    private data class AnchorState(
        var anchor: PointF,
        var anchorSinceMs: Long,
        var lastAlertMs: Long = 0L,
    )

    private val states = HashMap<Long, AnchorState>()

    fun detect(
        tracks: List<TrackedPerson>,
        nowMs: Long,
        durationMs: Long = loiterDurationMs,
        movementRadiusPx: Float = this.movementRadiusPx,
    ): List<AlertEvent> {
        val active = tracks.map { it.trackId }.toSet()
        states.keys.retainAll(active)
        val alerts = ArrayList<AlertEvent>()

        for (t in tracks) {
            val s = states.getOrPut(t.trackId) {
                AnchorState(anchor = t.centroid.copy(), anchorSinceMs = nowMs)
            }
            val moved = distancePx(t.centroid, s.anchor)
            if (moved > movementRadiusPx) {
                s.anchor = t.centroid.copy()
                s.anchorSinceMs = nowMs
                continue
            }

            val stayedMs = nowMs - s.anchorSinceMs
            if (stayedMs >= durationMs && nowMs - s.lastAlertMs >= cooldownMs) {
                s.lastAlertMs = nowMs
                alerts += AlertEvent(
                    type = AlertType.Loitering,
                    message = "Loitering: person ${t.trackId} stayed in one area for ${stayedMs / 1000}s",
                    timestampMs = nowMs,
                )
            }
        }
        return alerts
    }

    private fun PointF.copy() = PointF(x, y)

    private fun distancePx(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}
