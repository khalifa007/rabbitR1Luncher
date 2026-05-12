package com.r1.launcher.survey

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Per-call conversation state machine. Owns the [GptRealtimeClient] for one
 * survey call, builds the gpt-realtime-2 system prompt + tool schema, and
 * dispatches the model's tool calls into structured state.
 *
 * Step 1 (mandatory) = consent disclosure. The model cannot record any answer
 * before [Tool.CONSENT_RESPONSE] fires with granted=true. If granted=false,
 * the call is terminated immediately and stamped as [CallRecordStatus.CONSENT_DENIED].
 *
 * Step 2 = the survey questions, asked one at a time. After each answer the
 * model calls [Tool.RECORD_ANSWER]. The orchestrator persists the answer to
 * [state] (a live snapshot consumed by the live UI panel and post-call summary).
 *
 * Step 3 = the model calls [Tool.END_CALL] when finished, opt-out is requested,
 * or it can't make progress. The orchestrator's [Listener.onComplete] fires
 * with the reason; the host hangs up the SIP call and runs the post-call
 * pipeline (summary + email).
 */
class SurveyOrchestrator(
    private val survey: Survey,
    private val contact: Contact,
    private val consentText: String,
    private val voice: String,
    private val client: GptRealtimeClient,
    private val listener: Listener,
) : GptRealtimeClient.Callback {

    interface Listener {
        /** Best-effort live UI/state hook. Called whenever any field on
         *  [LiveState] changes (consent granted, answer recorded, etc.). */
        fun onStateChanged(state: LiveState)
        /** Streaming bot text — append to live transcript. */
        fun onAssistantTextDelta(text: String)
        /** Committed user-side transcription (per turn). */
        fun onUserTextFinal(text: String)
        /** PCM16 mono 24 kHz audio chunk to play into the SIP uplink. */
        fun onBotAudioChunk(pcm: ByteArray)
        /** Bot turn finished — uplink can drain its buffer. */
        fun onBotAudioDone()
        /** Terminal event — call should hang up + post-call pipeline runs. */
        fun onComplete(reason: String, granted: Boolean)
        fun onError(message: String)
    }

    /** Live snapshot of conversation state. Mutated in place by the orchestrator;
     *  consumers read after [Listener.onStateChanged]. */
    data class LiveState(
        var consentDecided: Boolean = false,
        var consentGranted: Boolean = false,
        /** question_id → answer, in order received from the model. */
        val answers: LinkedHashMap<String, String> = LinkedHashMap(),
        /** Optional model-reported confidence per answer (0.0..1.0). */
        val confidences: LinkedHashMap<String, Float> = LinkedHashMap(),
        var currentQuestionPrompt: String = "",
        var done: Boolean = false,
        var endReason: String? = null,
    )

    val state = LiveState()

    /** Concatenated bot transcript across all turns, for the persisted CallRecord. */
    private val transcriptAssistant = StringBuilder()
    /** Concatenated user-side transcript across all turns. */
    private val transcriptUser = StringBuilder()

    /** Returns the combined transcript as a labelled chat log. */
    fun renderTranscript(): String {
        val sb = StringBuilder()
        if (transcriptUser.isNotEmpty()) sb.append("User: ").append(transcriptUser).append('\n')
        if (transcriptAssistant.isNotEmpty()) sb.append("Bot: ").append(transcriptAssistant).append('\n')
        return sb.toString()
    }

    fun start() {
        // No-op: GptRealtimeClient.init already opened the WS. We just wait for
        // the session.updated → onSessionReady() callback below to kick the bot.
    }

    // ---- GptRealtimeClient.Callback ----

    override fun onConnected() {
        Log.i(TAG, "ws connected")
    }

    override fun onSessionReady() {
        Log.i(TAG, "session ready — telling model to start")
        client.createResponse(
            "Begin the call now by reading the consent disclosure verbatim. " +
            "Wait for the respondent's reply before doing anything else."
        )
    }

    override fun onUserSpeechStarted() {
        // Barge-in: if the bot is currently speaking and the user interrupts,
        // cancel the in-flight response so they can be heard.
        client.cancelResponse()
    }

    override fun onUserSpeechStopped() = Unit

    override fun onAudioOutDelta(pcm: ByteArray) {
        listener.onBotAudioChunk(pcm)
    }

    override fun onAudioOutDone() {
        listener.onBotAudioDone()
    }

    override fun onAssistantTranscriptDelta(text: String) {
        transcriptAssistant.append(text)
        listener.onAssistantTextDelta(text)
    }

    override fun onUserTranscriptFinal(text: String) {
        if (text.isNotBlank()) {
            transcriptUser.append(text).append('\n')
            listener.onUserTextFinal(text)
        }
    }

    override fun onToolCall(name: String, callId: String, args: JsonObject) {
        val result: JsonObject = when (name) {
            Tool.CONSENT_RESPONSE -> handleConsentResponse(args)
            Tool.RECORD_ANSWER -> handleRecordAnswer(args)
            Tool.END_CALL -> handleEndCall(args)
            else -> buildJsonObject { put("error", "unknown_tool"); put("tool", name) }
        }
        // Reply with the tool result. GptRealtimeClient.sendToolResult also calls
        // response.create so the model resumes speaking after the tool ack.
        client.sendToolResult(callId, result)
    }

    override fun onResponseDone() = Unit

    override fun onError(message: String) {
        Log.w(TAG, "rt error: $message")
        listener.onError(message)
    }

    override fun onDisconnected() {
        if (!state.done) {
            state.done = true
            state.endReason = "disconnected"
            listener.onStateChanged(state)
            listener.onComplete("disconnected", state.consentGranted)
        }
    }

    // ---- Tool handlers ----

    private fun handleConsentResponse(args: JsonObject): JsonObject {
        val granted = args["granted"]?.jsonPrimitive?.booleanOrNull ?: false
        state.consentDecided = true
        state.consentGranted = granted
        listener.onStateChanged(state)
        if (!granted) {
            // Consent denied — model should now thank + end_call. We don't
            // hang up here directly so the bot can deliver the closing line.
            return buildJsonObject {
                put("acknowledged", true)
                put("next_step", "thank the respondent for their time and call end_call with reason=consent_denied")
            }
        }
        return buildJsonObject {
            put("acknowledged", true)
            put("next_step", "begin asking the survey questions one at a time")
            put("total_questions", survey.questions.size)
        }
    }

    private fun handleRecordAnswer(args: JsonObject): JsonObject {
        if (!state.consentGranted) {
            // Out-of-order tool call. Ignore the answer and remind the model.
            return buildJsonObject {
                put("error", "consent_not_granted")
                put("hint", "consent_response must complete with granted=true before any record_answer call")
            }
        }
        val qid = args["question_id"]?.jsonPrimitive?.contentOrNull
            ?: return buildJsonObject { put("error", "missing_question_id") }
        val answer = args["answer"]?.jsonPrimitive?.contentOrNull ?: ""
        val conf = args["confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
        state.answers[qid] = answer
        state.confidences[qid] = conf
        // Update current-question-prompt to the NEXT unanswered question for UI.
        val nextQ = survey.questions.firstOrNull { !state.answers.containsKey(it.id) }
        state.currentQuestionPrompt = nextQ?.prompt ?: ""
        listener.onStateChanged(state)
        return buildJsonObject {
            put("acknowledged", true)
            put("answered", state.answers.size)
            put("remaining", survey.questions.size - state.answers.size)
            if (nextQ != null) {
                put("next_question_id", nextQ.id)
                put("next_question_prompt", nextQ.prompt)
            } else {
                put("all_questions_done", true)
                put("hint", "thank the respondent and call end_call with reason=completed")
            }
        }
    }

    private fun handleEndCall(args: JsonObject): JsonObject {
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "completed"
        state.done = true
        state.endReason = reason
        listener.onStateChanged(state)
        listener.onComplete(reason, state.consentGranted)
        return buildJsonObject { put("acknowledged", true) }
    }

    companion object {
        private const val TAG = "SurveyOrchestrator"

        /** Tool names exposed to gpt-realtime-2. */
        object Tool {
            const val CONSENT_RESPONSE = "consent_response"
            const val RECORD_ANSWER = "record_answer"
            const val END_CALL = "end_call"
        }

        /**
         * Build the session.update payload for [GptRealtimeClient].
         *
         * Per gpt-realtime-2 docs:
         *  - input/output audio = pcm16 (24 kHz mono)
         *  - turn_detection.server_vad handles end-of-turn detection
         *  - tool_choice="auto" lets the model decide when to call tools
         *  - input_audio_transcription enables user-side transcription via Whisper
         */
        fun buildSessionConfig(
            survey: Survey,
            contact: Contact,
            consentText: String,
            voice: String,
        ): JsonObject = buildJsonObject {
            put("modalities", buildJsonArray { add("audio"); add("text") })
            put("instructions", buildSystemPrompt(survey, contact, consentText))
            put("voice", voice)
            put("input_audio_format", "pcm16")
            put("output_audio_format", "pcm16")
            put("input_audio_transcription", buildJsonObject {
                put("model", "whisper-1")
            })
            put("turn_detection", buildJsonObject {
                put("type", "server_vad")
                put("threshold", 0.5)
                put("prefix_padding_ms", 300)
                put("silence_duration_ms", 600)
            })
            put("temperature", 0.7)
            put("tool_choice", "auto")
            put("tools", buildJsonArray {
                // consent_response
                add(buildJsonObject {
                    put("type", "function")
                    put("name", Tool.CONSENT_RESPONSE)
                    put("description",
                        "Record whether the respondent gave consent to continue the survey. " +
                        "Call this exactly once, after reading the consent disclosure and " +
                        "receiving a clear yes/no answer.")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("granted", buildJsonObject {
                                put("type", "boolean")
                                put("description", "true if the respondent agreed to continue")
                            })
                        })
                        put("required", buildJsonArray { add("granted") })
                    })
                })
                // record_answer
                add(buildJsonObject {
                    put("type", "function")
                    put("name", Tool.RECORD_ANSWER)
                    put("description",
                        "Record the respondent's answer to the CURRENT survey question. " +
                        "Call this exactly once per question, after the respondent has " +
                        "clearly answered. The answer field should be cleaned for the " +
                        "question type (e.g. just the number for NUMBER questions).")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("question_id", buildJsonObject { put("type", "string") })
                            put("answer", buildJsonObject { put("type", "string") })
                            put("confidence", buildJsonObject {
                                put("type", "number")
                                put("description", "0.0 (unsure) to 1.0 (very confident)")
                            })
                        })
                        put("required", buildJsonArray { add("question_id"); add("answer") })
                    })
                })
                // end_call
                add(buildJsonObject {
                    put("type", "function")
                    put("name", Tool.END_CALL)
                    put("description",
                        "Signal that the conversation is complete and the call should be " +
                        "terminated. Reason should be one of: completed, consent_denied, " +
                        "opt_out, no_progress, technical_failure.")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("reason", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add("reason") })
                    })
                })
            })
        }

        private fun buildSystemPrompt(
            survey: Survey,
            contact: Contact,
            consentText: String,
        ): String {
            val name = contact.name.ifBlank { "the respondent" }
            val qs = survey.questions.joinToString("\n") { q ->
                val typeHint = when (q.type) {
                    QuestionType.OPEN -> "open-ended"
                    QuestionType.MULTI_CHOICE -> "multiple choice; options: ${q.choices.joinToString(", ")}"
                    QuestionType.YES_NO -> "yes or no"
                    QuestionType.NUMBER -> "numeric"
                    QuestionType.RATING_1_5 -> "rating 1 to 5"
                }
                val followUp = q.followUpHint?.let { "  follow-up hint: $it" } ?: ""
                "- (id: ${q.id}) [$typeHint] ${q.prompt}$followUp"
            }
            val customInstructions = survey.systemInstructions?.takeIf { it.isNotBlank() }
                ?.let { "\n\nADDITIONAL INSTRUCTIONS FROM SURVEY OWNER:\n$it" } ?: ""
            return """
You are a polite, professional AI survey caller. You are speaking with $name.

STEP 1 — CONSENT (mandatory, must complete before any survey question):
Read this disclosure verbatim at the very start of the call:
"$consentText"

Then wait for $name's reply. Call the consent_response tool with granted=true if they
agreed to continue, granted=false otherwise. If granted=false, thank them briefly and
call end_call with reason=consent_denied. Do NOT proceed to step 2 unless granted=true.

STEP 2 — SURVEY:
Ask the following questions ONE AT A TIME. After each clear answer, call record_answer
with the question_id, the cleaned answer, and your confidence (0.0–1.0). You may ask
one or two clarifying follow-ups per question if the answer is ambiguous, but do not
loop — move on after at most 2 follow-ups. Skip questions politely if the respondent
refuses any specific question (record the answer as the literal string "skipped").

Questions:
$qs

When all questions are answered (or every remaining question has been skipped or asked
twice), thank the respondent warmly and call end_call with reason=completed.

GUARDRAILS:
- Never claim to be human. If asked "are you a robot/AI/bot", confirm politely: yes.
- If $name asks to be removed from the list or said "stop calling", call end_call with
  reason=opt_out after acknowledging.
- Match the respondent's language if it's clearly different from English; otherwise
  default to English.
- Keep responses short — under 2 sentences typical, never more than 4.
- Don't fabricate. If you don't understand an answer, ask once more then move on.$customInstructions
""".trim()
        }
    }
}
