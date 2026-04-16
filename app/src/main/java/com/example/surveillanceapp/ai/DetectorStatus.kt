package com.example.surveillanceapp.ai

data class DetectorStatus(
    val ready: Boolean = false,
    val error: String? = null,
    val inferenceMs: Long = 0L,
    val detections: Int = 0,
    /** Anchors with person score above a loose floor (0.01); helps tell parse vs threshold issues. */
    val rawPersonCandidates: Int = 0,
    /** Anchors with any class score above a loose floor (0.01). */
    val rawAnyClassCandidates: Int = 0,
    /** Anchors with person score above the active confidence threshold. */
    val thresholdPassedCandidates: Int = 0,
    /** Global strongest class index from the latest frame; useful for class-map mismatch diagnosis. */
    val strongestClassIndex: Int = -1,
    /** Strongest class confidence from the latest frame. */
    val strongestClassConfidence: Float = 0f,
    /** Logged once: TFLite output tensor shape after squeezing batch. */
    val modelOutputShape: String? = null,
)
