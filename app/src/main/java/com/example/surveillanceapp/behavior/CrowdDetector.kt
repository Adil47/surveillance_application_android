package com.example.surveillanceapp.behavior

class CrowdDetector(
    private val threshold: Int = 5,
    private val cooldownMs: Long = 5_000L,
) {
    private var lastAlertMs: Long = 0L

    fun detect(tracks: List<TrackedPerson>, nowMs: Long): List<AlertEvent> {
        val count = tracks.size
        if (count <= threshold) return emptyList()
        if (nowMs - lastAlertMs < cooldownMs) return emptyList()
        lastAlertMs = nowMs
        return listOf(
            AlertEvent(
                type = AlertType.Crowd,
                message = "Crowd detected: $count persons (> $threshold)",
                timestampMs = nowMs,
            ),
        )
    }
}
