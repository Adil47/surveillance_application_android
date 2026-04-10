package com.example.surveillanceapp.ai

import android.graphics.RectF

/**
 * One detected object in source-frame coordinates.
 */
data class DetectionResult(
    val boundingBox: RectF,
    val confidence: Float,
    val label: String,
)
