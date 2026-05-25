package com.r1.launcher.translator

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One source→target translation pair shown in the Translator panel.
 *
 * Stored as a pair (not two separate bubbles) so:
 *   - tap-to-replay-TTS has a stable id per *translation* (not per bubble)
 *   - copy / delete / re-translate operate on the unit the user thinks in
 *   - persistence + restore is a single record, not two
 *
 * `pending` indicates the translation hasn't returned yet — the target text
 * is blank and the bubble renders an indeterminate dot animation.
 * `error` carries the failure message and replaces the target bubble's text.
 */
@Serializable
data class TranslationMessage(
    val id: String = UUID.randomUUID().toString(),
    val sourceText: String,
    val sourceLang: String,
    val targetText: String = "",
    val targetLang: String,
    val pending: Boolean = false,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
