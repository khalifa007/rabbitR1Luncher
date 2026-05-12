package com.r1.launcher.survey

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QuestionType {
    @SerialName("open") OPEN,
    @SerialName("multi_choice") MULTI_CHOICE,
    @SerialName("yes_no") YES_NO,
    @SerialName("number") NUMBER,
    @SerialName("rating_1_5") RATING_1_5,
}

@Serializable
data class SurveyQuestion(
    val id: String,
    val prompt: String,
    val type: QuestionType,
    val choices: List<String> = emptyList(),
    val followUpHint: String? = null,
    val branching: Map<String, String> = emptyMap(),
)

@Serializable
data class Survey(
    val id: String,
    val name: String,
    val questions: List<SurveyQuestion>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val consentText: String? = null,
    val systemInstructions: String? = null,
)

@Serializable
data class SurveyIndexEntry(
    val id: String,
    val name: String,
    val questionCount: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Serializable
data class Contact(
    val name: String,
    val phone: String,
    val locale: String = "en",
)

@Serializable
enum class CampaignStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("paused") PAUSED,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class Campaign(
    val id: String,
    val surveyId: String,
    val contacts: List<Contact>,
    val status: CampaignStatus = CampaignStatus.PENDING,
    val nextContactIdx: Int = 0,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val callRecordIds: List<String> = emptyList(),
)

@Serializable
data class CampaignIndexEntry(
    val id: String,
    val surveyId: String,
    val contactCount: Int,
    val nextContactIdx: Int,
    val status: CampaignStatus,
    val createdAtMs: Long,
)

@Serializable
enum class CallRecordStatus {
    @SerialName("dialing") DIALING,
    @SerialName("ringing") RINGING,
    @SerialName("live") LIVE,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("no_answer") NO_ANSWER,
    @SerialName("busy") BUSY,
    @SerialName("consent_denied") CONSENT_DENIED,
}

@Serializable
data class CallRecord(
    val id: String,
    val campaignId: String,
    val surveyId: String,
    val contact: Contact,
    val createdAtMs: Long,
    var durationMs: Long = 0L,
    var status: CallRecordStatus = CallRecordStatus.DIALING,
    var audioPath: String,
    var transcript: String? = null,
    var structuredAnswers: Map<String, String> = emptyMap(),
    var summary: String? = null,
    var sentiment: String? = null,
    var completeness: Float = 0f,
    var endReason: String? = null,
    var errorMessage: String? = null,
)

@Serializable
data class CallRecordIndexEntry(
    val id: String,
    val campaignId: String,
    val surveyId: String,
    val contactName: String,
    val contactPhone: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val status: CallRecordStatus,
)

enum class SurveyDetailAction { PLAY_TOGGLE, EMAIL, RETRY_SUMMARY, DELETE, CLOSE }
