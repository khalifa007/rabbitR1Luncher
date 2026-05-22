package com.r1.launcher.media

data class CaptureItem(
    val name: String,
    val kind: String,        // "image" | "video"
    val sizeBytes: Long,
    val takenAt: Long,
    val durationMs: Long?,
    val url: String,
    val thumbUrl: String,
)
