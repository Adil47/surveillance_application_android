package com.example.surveillanceapp.behavior

import android.graphics.PointF

class RestrictedAreaDetector(
    zones: List<List<PointF>>,
    private val cooldownMs: Long = 3_000L,
) {
    private val lastAlertByTrack = HashMap<Long, Long>()
    @Volatile
    private var zonesRef: List<List<PointF>> = zones

    fun setZones(zones: List<List<PointF>>) {
        zonesRef = zones
    }

    fun detect(tracks: List<TrackedPerson>, nowMs: Long): List<AlertEvent> {
        val zones = zonesRef
        if (zones.isEmpty()) return emptyList()
        val alerts = ArrayList<AlertEvent>()
        for (t in tracks) {
            val inside = zones.any { polygon -> containsPoint(polygon, t.centroid) }
            if (!inside) continue
            val last = lastAlertByTrack[t.trackId] ?: 0L
            if (nowMs - last < cooldownMs) continue
            lastAlertByTrack[t.trackId] = nowMs
            alerts += AlertEvent(
                type = AlertType.RestrictedAreaIntrusion,
                message = "Restricted area intrusion by person ${t.trackId}",
                timestampMs = nowMs,
            )
        }
        return alerts
    }

    // Ray casting polygon test.
    private fun containsPoint(poly: List<PointF>, p: PointF): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            val intersect = ((pi.y > p.y) != (pj.y > p.y)) &&
                (p.x < (pj.x - pi.x) * (p.y - pi.y) / ((pj.y - pi.y).coerceAtLeast(0.0001f)) + pi.x)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }
}
