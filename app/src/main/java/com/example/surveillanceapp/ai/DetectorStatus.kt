package com.example.surveillanceapp.ai

data class DetectorStatus(
    val ready: Boolean = false,
    val error: String? = null,
    val inferenceMs: Long = 0L,
    val detections: Int = 0,
)
