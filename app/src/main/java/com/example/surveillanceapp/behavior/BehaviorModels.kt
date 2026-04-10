package com.example.surveillanceapp.behavior

import android.graphics.PointF
import android.graphics.RectF

enum class AlertType {
    Loitering,
    RestrictedAreaIntrusion,
    Crowd,
}

data class AlertEvent(
    val type: AlertType,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class TrackedPerson(
    val trackId: Long,
    val bbox: RectF,
    val centroid: PointF,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val confidence: Float,
)
