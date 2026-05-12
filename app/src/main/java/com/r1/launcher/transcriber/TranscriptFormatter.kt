package com.r1.launcher.transcriber

/**
 * Render a Scribe diarized response as plaintext with one block per speaker
 * turn. Words from the same speaker with no gap > [SAME_TURN_GAP_SEC] of
 * silence are grouped into a single line:
 *
 *   [00:00] Alice: Hello, can everyone hear me?
 *   [00:04] Bob: Yes, you're clear.
 *
 * Speaker IDs from Scribe are `speaker_0`, `speaker_1`, etc. The
 * [speakerNames] map can override these with human names.
 */
object TranscriptFormatter {

    private const val SAME_TURN_GAP_SEC = 1.5f

    fun render(
        response: ScribeResponse,
        speakerNames: Map<String, String> = emptyMap(),
    ): String {
        if (response.words.isEmpty()) return response.text.trim()

        val sb = StringBuilder()
        var currentSpeaker: String? = null
        var currentLineWords = mutableListOf<String>()
        var currentLineStartSec = 0f
        var lastEndSec = 0f

        fun flush() {
            if (currentLineWords.isEmpty()) return
            val name = currentSpeaker?.let { speakerNames[it] ?: humanizeSpeakerId(it) } ?: "speaker"
            sb.append('[').append(formatMmSs(currentLineStartSec)).append("] ")
                .append(name).append(": ")
                .append(currentLineWords.joinToString(" ").replace(" ,", ",").replace(" .", ".").replace(" ?", "?").replace(" !", "!"))
                .append('\n')
            currentLineWords = mutableListOf()
        }

        for (w in response.words) {
            val text = w.text.trim()
            if (text.isEmpty() || w.type == "spacing") {
                lastEndSec = w.end
                continue
            }
            val speaker = w.speaker_id ?: "speaker_0"
            val gap = w.start - lastEndSec
            if (currentSpeaker != speaker || (currentLineWords.isNotEmpty() && gap > SAME_TURN_GAP_SEC)) {
                flush()
                currentSpeaker = speaker
                currentLineStartSec = w.start
            }
            currentLineWords.add(text)
            lastEndSec = w.end
        }
        flush()
        return sb.toString().trim()
    }

    fun distinctSpeakerCount(response: ScribeResponse): Int =
        response.words.mapNotNull { it.speaker_id }.toSet().size

    fun distinctSpeakerIds(response: ScribeResponse): List<String> =
        response.words.mapNotNull { it.speaker_id }.toSet().sorted()

    private fun formatMmSs(seconds: Float): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val mm = total / 60
        val ss = total % 60
        return "%02d:%02d".format(mm, ss)
    }

    /** Turn `speaker_0` → `speaker 1` (one-indexed for human readability). */
    fun humanizeSpeakerId(raw: String): String {
        val n = raw.removePrefix("speaker_").toIntOrNull() ?: return raw
        return "speaker ${n + 1}"
    }
}
