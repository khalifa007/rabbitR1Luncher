package com.r1.launcher.survey

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One-shot post-call summarizer. Reads a finished [CallRecord] + its [Survey],
 * runs a single chat-completion request, and returns a structured summary the
 * post-call pipeline writes back to the record and into the email body.
 *
 * Two backends, selected by the model id prefix in [SurveyPrefs.summarizerModel]:
 *   - "claude-*"  → Anthropic Messages API (`https://api.anthropic.com/v1/messages`)
 *   - "gpt-*"     → OpenAI Chat Completions  (`https://api.openai.com/v1/chat/completions`)
 *
 * The model is asked to return strict JSON with four fields:
 *   {
 *     "summary":      "<1-3 sentence executive summary>",
 *     "sentiment":    "positive|neutral|negative|mixed",
 *     "completeness": <0..1 float — how many of the questions got real answers>,
 *     "flags":        ["follow_up_needed", "consent_issues", ...]
 *   }
 *
 * Robust to the model wrapping the JSON in prose or markdown fences — the
 * parser locates the first `{…}` block and parses it. On any failure (HTTP,
 * JSON, missing field) it returns a [Result.Failure] carrying the error
 * message; the pipeline falls back to mailing the call without an LLM summary.
 */
class SummaryGenerator(
    private val model: String,
    private val apiKey: String,
) {

    data class SummaryResult(
        val summary: String,
        val sentiment: String,
        val completeness: Float,
        val flags: List<String>,
    )

    sealed class Result {
        data class Success(val data: SummaryResult) : Result()
        data class Failure(val message: String) : Result()
    }

    fun generate(record: CallRecord, survey: Survey?): Result {
        if (apiKey.isBlank()) return Result.Failure("no api key for $model")
        val prompt = buildPrompt(record, survey)
        return when {
            model.startsWith("claude") -> callAnthropic(prompt)
            model.startsWith("gpt")    -> callOpenAi(prompt)
            else -> Result.Failure("unsupported model: $model")
        }
    }

    private fun callAnthropic(userPrompt: String): Result {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 1024)
            put("system", SYSTEM_PROMPT)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toRequestBody(JSON_MIME))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = extractErrorMessage(raw) ?: "anthropic HTTP ${resp.code}"
                    return@use Result.Failure(msg)
                }
                val root = json.parseToJsonElement(raw).jsonObject
                val text = root["content"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: return@use Result.Failure("anthropic: no text content")
                parseSummaryJson(text)
            }
        }.getOrElse { Result.Failure(it.message ?: "anthropic call failed") }
    }

    private fun callOpenAi(userPrompt: String): Result {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system"); put("content", SYSTEM_PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user"); put("content", userPrompt)
                })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(body.toRequestBody(JSON_MIME))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = extractErrorMessage(raw) ?: "openai HTTP ${resp.code}"
                    return@use Result.Failure(msg)
                }
                val root = json.parseToJsonElement(raw).jsonObject
                val text = root["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
                    ?: return@use Result.Failure("openai: no message content")
                parseSummaryJson(text)
            }
        }.getOrElse { Result.Failure(it.message ?: "openai call failed") }
    }

    private fun parseSummaryJson(text: String): Result {
        // Locate the JSON object even if the model wrapped it in prose or
        // ```json fences. Anthropic without a `response_format` knob is the
        // common case.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return Result.Failure("no json in summary: ${text.take(120)}")
        }
        return runCatching {
            val obj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            Result.Success(SummaryResult(
                summary = obj["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                sentiment = obj["sentiment"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "neutral" },
                completeness = obj["completeness"]?.jsonPrimitive?.floatOrNull
                    ?.coerceIn(0f, 1f) ?: 0f,
                flags = obj["flags"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?: emptyList(),
            ))
        }.getOrElse { Result.Failure("bad summary json: ${it.message}") }
    }

    private fun extractErrorMessage(raw: String): String? = runCatching {
        val o = json.parseToJsonElement(raw).jsonObject
        o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: o["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        private const val TAG = "SurveyPostProc"
        private val JSON_MIME = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        private const val SYSTEM_PROMPT = """
You are an analyst summarizing a single completed AI-conducted survey phone call.
Output MUST be a single JSON object, no prose, no markdown fences. Fields:
{
  "summary":      "1 to 3 sentences. What did the respondent say, in plain language. Reference specific answers.",
  "sentiment":    "one of: positive, neutral, negative, mixed",
  "completeness": "float 0..1. 1.0 = every question answered with a substantive answer. 0.0 = no useful answers. Skipped / 'no comment' questions count partial.",
  "flags":        ["follow_up_needed", "consent_issues", "opt_out_requested", "unclear_audio", "non_target_respondent"]
}
Only emit flags that apply. Empty list if none. Never invent answers the
respondent did not give. If the call was very short or the consent was denied,
say so in the summary explicitly.
"""

        private fun buildPrompt(record: CallRecord, survey: Survey?): String {
            val contact = record.contact
            val durationS = record.durationMs / 1000
            val mins = durationS / 60
            val secs = durationS % 60
            val sb = StringBuilder()
            sb.appendLine("Call metadata:")
            sb.appendLine("  contact: ${contact.name.ifBlank { "(unnamed)" }} <${contact.phone}>")
            sb.appendLine("  status:  ${record.status.name.lowercase()}")
            sb.appendLine("  ended:   ${record.endReason ?: "(unknown)"}")
            sb.appendLine("  duration: ${"%d:%02d".format(mins, secs)}")
            if (survey != null) {
                sb.appendLine()
                sb.appendLine("Survey: ${survey.name} (${survey.questions.size} questions)")
                survey.questions.forEach { q ->
                    sb.appendLine("  - ${q.id} [${q.type.name.lowercase()}] ${q.prompt}")
                }
            }
            sb.appendLine()
            sb.appendLine("Structured answers (question_id → answer):")
            if (record.structuredAnswers.isEmpty()) {
                sb.appendLine("  (none captured)")
            } else {
                record.structuredAnswers.forEach { (qid, ans) ->
                    sb.appendLine("  - $qid → $ans")
                }
            }
            val transcript = record.transcript
            if (!transcript.isNullOrBlank()) {
                sb.appendLine()
                sb.appendLine("Full transcript:")
                sb.appendLine(transcript.take(8000))   // keep tokens reasonable
            }
            sb.appendLine()
            sb.appendLine("Return the JSON object only.")
            return sb.toString()
        }

        fun forModel(model: String, apiKey: String): SummaryGenerator =
            SummaryGenerator(model, apiKey).also {
                Log.i(TAG, "summary backend = $model")
            }
    }
}
