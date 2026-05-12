package com.r1.launcher.transcriber

import kotlinx.serialization.Serializable

/** Subset of the ElevenLabs Scribe v2 batch response we care about. */
@Serializable
data class ScribeResponse(
    val language_code: String? = null,
    val language_probability: Float? = null,
    val text: String = "",
    val words: List<ScribeWord> = emptyList(),
)

@Serializable
data class ScribeWord(
    val text: String = "",
    val start: Float = 0f,
    val end: Float = 0f,
    /** Either "word" or "spacing" — Scribe sometimes inserts pure-space
     *  entries between word tokens for diarization-aware reflow. */
    val type: String? = null,
    val speaker_id: String? = null,
)
