package com.r1.launcher.transcriber

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted state for a single meeting recording. */
@Serializable
enum class MeetingStatus {
    @SerialName("recording") RECORDING,
    @SerialName("queued") QUEUED,
    @SerialName("transcribing") TRANSCRIBING,
    @SerialName("transcribed") TRANSCRIBED,
    @SerialName("failed") FAILED,
}

@Serializable
data class Meeting(
    val uuid: String,
    val title: String,
    val createdAtMs: Long,
    var durationMs: Long = 0L,
    var status: MeetingStatus = MeetingStatus.RECORDING,
    val audioPath: String,
    var transcriptJson: String? = null,
    var transcriptText: String? = null,
    var languageCode: String? = null,
    var speakerCount: Int = 0,
    var speakerNames: Map<String, String> = emptyMap(),
    var errorMessage: String? = null,
    /** Snapshot of the ElevenLabs key at record-start so a mid-recording
     *  Settings → Voice → "clear key" doesn't break the upload. Cleared after
     *  successful transcription. */
    var apiKeySnapshot: String? = null,
)

/** Index entry kept in `filesDir/transcriber/index.json` for fast list rendering
 *  without parsing every per-meeting JSON. */
@Serializable
data class MeetingIndexEntry(
    val uuid: String,
    val title: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val status: MeetingStatus,
    val speakerCount: Int,
)

/** Discrete actions the detail-page ⋮ menu can offer. The set shown depends on
 *  the meeting's current status — host computes it on menu open. */
enum class TranscriberDetailAction { PLAY_TOGGLE, EMAIL, RETRY, DELETE, CLOSE }
