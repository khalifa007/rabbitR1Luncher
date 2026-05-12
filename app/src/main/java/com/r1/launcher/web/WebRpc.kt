package com.r1.launcher.web

import android.content.Context
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import com.r1.launcher.messages.SmsLoader
import com.r1.launcher.survey.Contact
import com.r1.launcher.survey.QuestionType
import com.r1.launcher.survey.Survey
import com.r1.launcher.survey.SurveyPrefs
import com.r1.launcher.survey.SurveyQuestion
import com.r1.launcher.survey.SurveyStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON-RPC dispatcher for the embedded web panel. Mirrors the request/response
 * shape used by [com.r1.launcher.openclaw.GatewaySession] so the server-side
 * patterns and the client-side RPC helper share a wire vocabulary.
 *
 * Methods are thin shims over [LauncherHost] / [LauncherState] / [SmsLoader].
 * No business logic lives here — keep this file boring.
 */
class RpcException(val code: String, message: String) : RuntimeException(message)

object WebRpc {

    fun dispatch(
        host: LauncherHost,
        state: LauncherState,
        ctx: Context,
        method: String,
        params: JsonObject?,
    ): JsonElement = when (method) {
        "state.snapshot" -> buildSnapshot(state, ctx)

        "sms.list" -> buildSmsList(ctx)
        "sms.thread" -> buildSmsThread(ctx, params.requireString("address"))

        "text.send" -> handleTextSend(host, state, params)

        "wifi.toggle" -> {
            host.toggleWifi(params.requireBool("on")); JsonNull
        }
        "cellular.toggle" -> {
            host.toggleCellular(params.requireBool("on")); JsonNull
        }
        "bt.toggle" -> {
            host.toggleBluetooth(params.requireBool("on")); JsonNull
        }
        "hotspot.toggle" -> {
            host.toggleWifiShare(params.requireBool("on")); JsonNull
        }
        "brightness.set" -> {
            host.setBrightness(params.requireInt("level").coerceIn(1, 255)); JsonNull
        }
        "volume.set" -> {
            val v = params.requireInt("level").coerceAtLeast(0).coerceAtMost(state.volumeMax)
            host.setVolume(v); JsonNull
        }
        "voice.set_key" -> {
            host.voiceSaveKey(params.requireString("key")); JsonNull
        }
        "voice.set_custom_id" -> {
            // Empty string = clear the override (fall back to catalog voice).
            val v = params.requireString("id").trim()
            if (v.isEmpty()) host.voiceClearCustomVoiceId()
            else host.voiceSaveCustomVoiceId(v)
            JsonNull
        }
        "voice.clear_custom_id" -> {
            host.voiceClearCustomVoiceId(); JsonNull
        }

        "terminal.run" -> {
            requireWebTerminal(state)
            host.terminalRun(params.requireString("cmd")); JsonNull
        }
        "terminal.clear" -> {
            requireWebTerminal(state)
            host.terminalClear(); JsonNull
        }
        "terminal.history" -> {
            requireWebTerminal(state)
            buildJsonObject {
                put("cwd", state.terminalCwd)
                put("busy", state.terminalBusy)
                put("lines", buildJsonArray {
                    state.terminalOutput.toList().forEach { add(JsonPrimitive(it)) }
                })
            }
        }

        "claude.send" -> {
            host.claudeSend(params.requireString("text")); JsonNull
        }
        "claude.clear" -> {
            host.claudeClear(); JsonNull
        }
        "claude.auth.status" -> {
            val s = host.claudeAuthStatus()
            buildJsonObject {
                put("hasOAuth", s.hasOAuth)
                put("hasApiKey", s.hasApiKey)
                put("chrootReady", s.chrootReady)
            }
        }
        "claude.auth.start" -> {
            // Blocks for several seconds while the Anthropic SDK warms up
            // and prints the OAuth URL. Web client should show a spinner.
            val r = host.claudeAuthStart()
            buildJsonObject {
                put("url", r.url)
                put("log", r.log)
                r.error?.let { put("error", it) }
            }
        }
        "claude.auth.finish" -> {
            val r = host.claudeAuthFinish(params.requireString("code"))
            buildJsonObject {
                put("ok", r.ok)
                put("log", r.log)
                r.error?.let { put("error", it) }
            }
        }
        "claude.auth.api_key" -> {
            val ok = host.claudeSaveApiKey(params.requireString("key"))
            buildJsonObject { put("ok", ok) }
        }
        "claude.auth.reset" -> {
            // Nukes both credential files + the auth FIFO/log + .anthropic_key
            // so the user can re-attempt OAuth without a stale code_challenge
            // or half-written .credentials.json blocking the flow.
            val ok = host.claudeAuthReset()
            buildJsonObject { put("ok", ok) }
        }
        "claude.auth.verify" -> {
            val r = host.claudeAuthVerify()
            buildJsonObject {
                put("ok", r.ok)
                put("log", r.log)
                r.error?.let { put("error", it) }
            }
        }
        "claude.setup.start" -> {
            // Returns immediately — progress streams as `claude.setup.progress`
            // events, terminal status as `claude.setup.done`. Web UI subscribes
            // to those instead of waiting on this response.
            val started = host.claudeSetupStart()
            buildJsonObject { put("started", started); put("running", host.claudeSetupRunning()) }
        }
        "claude.setup.status" -> buildJsonObject {
            put("running", host.claudeSetupRunning())
        }
        "claude.history" -> buildJsonObject {
            put("busy", state.claudeBusy)
            put("streaming", state.claudeStreamingText)
            put("messages", buildJsonArray {
                state.claudeMessages.toList().forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("text", m.text)
                        put("error", m.error)
                    })
                }
            })
        }

        // --- meetings (transcriber) ---
        "transcriber.list" -> buildJsonObject {
            put("recording", state.recordingActive)
            put("transcribing", state.transcribeBusy)
            put("hasSmtp", state.hasSmtp)
            put("defaultRecipient", state.defaultRecipientDisplay)
            put("meetings", buildJsonArray {
                com.r1.launcher.transcriber.MeetingStore.get(ctx).listMeetings().forEach { m ->
                    add(buildJsonObject {
                        put("uuid", m.uuid)
                        put("title", m.title)
                        put("createdAtMs", m.createdAtMs)
                        put("durationMs", m.durationMs)
                        put("status", m.status.name.lowercase())
                        put("speakerCount", m.speakerCount)
                    })
                }
            })
        }
        "transcriber.delete" -> {
            host.transcriberDelete(params.requireString("uuid")); JsonNull
        }
        "transcriber.email" -> {
            host.transcriberShareEmail(
                params.requireString("uuid"),
                params.requireString("recipient"),
            ); JsonNull
        }
        "transcriber.start" -> {
            host.transcriberStartRecording(); JsonNull
        }
        "transcriber.stop" -> {
            host.transcriberStopRecording(); JsonNull
        }
        "transcriber.retry" -> {
            host.transcriberRetryTranscribe(params.requireString("uuid")); JsonNull
        }

        // --- survey call bot (Surveyor) ---
        "survey.list" -> buildSurveyList(ctx)
        "survey.get" -> buildSurveyFull(ctx, params.requireString("id"))
        "survey.create", "survey.update" -> {
            val s = parseSurveyFromParams(params)
            val id = host.surveyUpsertSurvey(s)
            buildJsonObject { put("id", id) }
        }
        "survey.delete" -> {
            host.surveyDeleteSurveyById(params.requireString("id")); JsonNull
        }
        "campaign.list" -> buildCampaignList(ctx)
        "campaign.get" -> buildCampaignFull(ctx, params.requireString("id"))
        "campaign.create" -> {
            val surveyId = params.requireString("surveyId")
            val contacts = parseContactsFromParams(params)
            if (contacts.isEmpty()) throw RpcException("empty_contacts", "no contacts provided")
            val cid = host.surveyCreateCampaign(surveyId, contacts)
            buildJsonObject { put("id", cid) }
        }
        "campaign.cancel" -> {
            host.surveyCancelCampaignById(params.requireString("id")); JsonNull
        }
        "campaign.start" -> {
            host.surveyStartCampaignById(params.requireString("id")); JsonNull
        }
        "call.list" -> buildCallList(ctx, params?.get("campaignId")?.jsonPrimitive?.contentOrNull)
        "call.get" -> buildCallFull(ctx, params.requireString("id"))
        "call.delete" -> {
            host.surveyDeleteCallRecord(params.requireString("id")); JsonNull
        }
        "call.email" -> {
            host.surveyEmailCallRecord(params.requireString("id")); JsonNull
        }
        "call.retry_summary" -> {
            host.surveyRetrySummary(params.requireString("id")); JsonNull
        }
        "survey.settings.get" -> buildSurveySettings(state)
        "survey.settings.set" -> {
            host.surveySaveSettingsField(
                params.requireString("field"),
                params.requireString("value"),
            ); JsonNull
        }
        "survey.live.state" -> buildSurveyLiveState(state)

        else -> throw RpcException("unknown_method", "unknown method: $method")
    }

    // ---- survey helpers ----

    private fun buildSurveyList(ctx: Context): JsonArray = buildJsonArray {
        SurveyStore.get(ctx).listSurveys().forEach { s ->
            add(buildJsonObject {
                put("id", s.id)
                put("name", s.name)
                put("questionCount", s.questionCount)
                put("createdAtMs", s.createdAtMs)
                put("updatedAtMs", s.updatedAtMs)
            })
        }
    }

    private fun buildSurveyFull(ctx: Context, id: String): JsonElement {
        val s = SurveyStore.get(ctx).loadSurvey(id) ?: return JsonNull
        return buildJsonObject {
            put("id", s.id)
            put("name", s.name)
            put("createdAtMs", s.createdAtMs)
            put("updatedAtMs", s.updatedAtMs)
            put("consentText", s.consentText)
            put("systemInstructions", s.systemInstructions)
            put("questions", buildJsonArray {
                s.questions.forEach { q ->
                    add(buildJsonObject {
                        put("id", q.id)
                        put("prompt", q.prompt)
                        put("type", q.type.wireName())
                        put("choices", buildJsonArray { q.choices.forEach { add(JsonPrimitive(it)) } })
                        put("followUpHint", q.followUpHint)
                    })
                }
            })
        }
    }

    private fun buildCampaignList(ctx: Context): JsonArray {
        val store = SurveyStore.get(ctx)
        val surveysById = store.listSurveys().associateBy { it.id }
        return buildJsonArray {
            store.listCampaigns().forEach { c ->
                add(buildJsonObject {
                    put("id", c.id)
                    put("surveyId", c.surveyId)
                    put("surveyName", surveysById[c.surveyId]?.name ?: "(missing)")
                    put("contactCount", c.contactCount)
                    put("nextContactIdx", c.nextContactIdx)
                    put("status", c.status.name.lowercase())
                    put("createdAtMs", c.createdAtMs)
                })
            }
        }
    }

    private fun buildCampaignFull(ctx: Context, id: String): JsonElement {
        val store = SurveyStore.get(ctx)
        val c = store.loadCampaign(id) ?: return JsonNull
        return buildJsonObject {
            put("id", c.id)
            put("surveyId", c.surveyId)
            put("status", c.status.name.lowercase())
            put("nextContactIdx", c.nextContactIdx)
            put("createdAtMs", c.createdAtMs)
            put("contacts", buildJsonArray {
                c.contacts.forEach { contact ->
                    add(buildJsonObject {
                        put("name", contact.name)
                        put("phone", contact.phone)
                        put("locale", contact.locale)
                    })
                }
            })
        }
    }

    private fun buildCallList(ctx: Context, campaignFilter: String?): JsonArray {
        val store = SurveyStore.get(ctx)
        val rows = if (campaignFilter.isNullOrBlank()) store.listCallRecords()
                   else store.listCallRecordsForCampaign(campaignFilter)
        return buildJsonArray {
            rows.forEach { r ->
                add(buildJsonObject {
                    put("id", r.id)
                    put("campaignId", r.campaignId)
                    put("surveyId", r.surveyId)
                    put("contactName", r.contactName)
                    put("contactPhone", r.contactPhone)
                    put("createdAtMs", r.createdAtMs)
                    put("durationMs", r.durationMs)
                    put("status", r.status.name.lowercase())
                })
            }
        }
    }

    private fun buildCallFull(ctx: Context, id: String): JsonElement {
        val r = SurveyStore.get(ctx).loadCallRecord(id) ?: return JsonNull
        return buildJsonObject {
            put("id", r.id)
            put("campaignId", r.campaignId)
            put("surveyId", r.surveyId)
            put("createdAtMs", r.createdAtMs)
            put("durationMs", r.durationMs)
            put("status", r.status.name.lowercase())
            put("endReason", r.endReason)
            put("summary", r.summary)
            put("sentiment", r.sentiment)
            put("completeness", r.completeness)
            put("transcript", r.transcript)
            put("contact", buildJsonObject {
                put("name", r.contact.name)
                put("phone", r.contact.phone)
                put("locale", r.contact.locale)
            })
            put("answers", buildJsonObject {
                r.structuredAnswers.forEach { (k, v) -> put(k, v) }
            })
            put("audioUrl", "/api/survey/audio/${r.campaignId}/${r.id}.wav")
        }
    }

    private fun buildSurveySettings(state: LauncherState): JsonObject = buildJsonObject {
        put("hasOpenAiKey", state.hasOpenAiKey)
        put("openAiKeyTail", state.openAiKeyTail)
        put("hasSipCreds", state.hasSipCreds)
        put("sipHost", state.sipHostDisplay)
        put("sipUser", state.sipUserDisplay)
        put("sipFrom", state.sipFromDisplay)
        put("voice", state.realtimeVoiceDisplay)
        put("summarizerModel", state.summarizerModelDisplay)
        put("consentText", state.surveyConsentTextDisplay)
        put("emailRecipient", state.surveyEmailRecipientDisplay)
        put("voiceCatalog", buildJsonArray {
            SurveyPrefs.REALTIME_VOICES.forEach { add(JsonPrimitive(it)) }
        })
        put("summarizerCatalog", buildJsonArray {
            SurveyPrefs.SUMMARIZER_MODELS.forEach { (label, id) ->
                add(buildJsonObject { put("label", label); put("id", id) })
            }
        })
    }

    private fun buildSurveyLiveState(state: LauncherState): JsonObject = buildJsonObject {
        put("active", state.surveyCallActive)
        put("campaignId", state.currentCampaignId)
        put("recordId", state.currentCallRecordId)
        put("status", state.surveyCallStatus)
        put("contactName", state.surveyCallContactName)
        put("currentQuestion", state.surveyCallCurrentQuestion)
        put("elapsedMs", state.surveyCallElapsedMs)
    }

    private fun parseSurveyFromParams(params: JsonObject?): Survey {
        if (params == null) throw RpcException("missing_body", "survey body required")
        val id = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val name = params.requireString("name").trim()
        if (name.isBlank()) throw RpcException("bad_name", "survey name is blank")
        val consentText = params["consentText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val systemInstructions = params["systemInstructions"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val questionsArr = params["questions"] as? JsonArray
            ?: throw RpcException("bad_questions", "questions array required")
        if (questionsArr.isEmpty()) throw RpcException("empty_questions", "at least one question required")
        var nextAutoIdx = 1
        val questions = questionsArr.map { qElem ->
            val qo = qElem as? JsonObject
                ?: throw RpcException("bad_question", "questions must be objects")
            val qid = qo["id"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "q_${nextAutoIdx++}"
            val prompt = qo.requireString("prompt").trim()
            if (prompt.isBlank()) throw RpcException("bad_prompt", "question prompt blank")
            val type = parseQuestionType(qo["type"]?.jsonPrimitive?.contentOrNull)
            val choices = (qo["choices"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: emptyList()
            val followUpHint = qo["followUpHint"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            SurveyQuestion(
                id = qid,
                prompt = prompt,
                type = type,
                choices = choices,
                followUpHint = followUpHint,
            )
        }
        return Survey(
            id = id,
            name = name,
            questions = questions,
            createdAtMs = 0L,    // overwritten in surveyUpsertSurvey
            updatedAtMs = 0L,
            consentText = consentText,
            systemInstructions = systemInstructions,
        )
    }

    private fun parseQuestionType(wire: String?): QuestionType = when (wire?.trim()?.lowercase()) {
        "multi_choice" -> QuestionType.MULTI_CHOICE
        "yes_no" -> QuestionType.YES_NO
        "number" -> QuestionType.NUMBER
        "rating_1_5" -> QuestionType.RATING_1_5
        "open", null, "" -> QuestionType.OPEN
        else -> throw RpcException("bad_question_type", "unknown question type: $wire")
    }

    private fun QuestionType.wireName(): String = when (this) {
        QuestionType.OPEN -> "open"
        QuestionType.MULTI_CHOICE -> "multi_choice"
        QuestionType.YES_NO -> "yes_no"
        QuestionType.NUMBER -> "number"
        QuestionType.RATING_1_5 -> "rating_1_5"
    }

    private fun parseContactsFromParams(params: JsonObject?): List<Contact> {
        val arr = params?.get("contacts") as? JsonArray
            ?: throw RpcException("bad_contacts", "contacts array required")
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val phone = o["phone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val locale = o["locale"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: "en"
            if (phone.isBlank()) return@mapNotNull null
            Contact(name = name, phone = phone, locale = locale)
        }
    }

    /** Gate the web-terminal methods behind the explicit opt-in toggle so that
     *  the launcher's root shell isn't silently exposed to anyone on the LAN. */
    private fun requireWebTerminal(state: LauncherState) {
        if (!state.webTerminalEnabled) {
            throw RpcException(
                "disabled",
                "Enable in Settings → Network → remote terminal",
            )
        }
    }

    fun buildSnapshot(state: LauncherState, ctx: Context? = null): JsonObject = buildJsonObject {
        // Locale lets the web companion render English/Arabic + flip RTL in
        // sync with the device. Default "en" when ctx isn't available — this
        // only happens in callers that didn't thread ctx through; fix at
        // call site rather than degrading silently elsewhere.
        put("locale", ctx?.let { com.r1.launcher.locale.LocalePrefs.get(it).language } ?: "en")
        put("system", buildJsonObject {
            put("battery", state.batteryPct)
            put("charging", state.batteryCharging)
            put("ip", state.webServerIp)
            put("port", state.webServerPort)
            put("clockText", state.clockText)
            put("dateText", state.dateText)
        })
        put("network", buildJsonObject {
            put("wifi", state.wifiEnabled)
            put("cellular", state.cellularOn)
            put("bt", state.btOn)
            put("hotspot", state.wifiShareEnabled)
            put("hotspotClients", state.wifiShareConnectedClients.size)
            put("signal", state.signalLevel)
            put("operator", state.simOperator)
            put("networkType", state.networkType)
            put("simPresent", state.simPresent)
        })
        put("brightness", state.brightnessLevel)
        put("volume", state.volumeLevel)
        put("volumeMax", state.volumeMax)
        put("openclaw", buildJsonObject {
            put("status", state.chatStatus)
            put("hasVoiceKey", state.hasVoiceKey)
            put("voiceKeyTail", state.voiceKeyTail)
            put("voiceEnabled", state.voiceEnabled)
            put("voiceId", state.voiceId)
            // Empty when no override is set; UI shows "using catalog voice".
            put("voiceCustomId", state.voiceCustomId)
        })
        put("terminal", buildJsonObject {
            put("enabled", state.webTerminalEnabled)
            put("cwd", state.terminalCwd)
            put("busy", state.terminalBusy)
        })
        put("claude", buildJsonObject {
            put("busy", state.claudeBusy)
            put("messageCount", state.claudeMessages.size)
        })
    }

    private fun buildSmsList(ctx: Context): JsonArray {
        val convs = runCatching { SmsLoader.loadConversations(ctx) }.getOrDefault(emptyList())
        return buildJsonArray {
            convs.forEach { c ->
                add(buildJsonObject {
                    put("address", c.address)
                    put("name", c.displayName)
                    put("latestBody", c.latestBody)
                    put("latestTimestampMs", c.latestTimestampMs)
                    put("unreadCount", c.unreadCount)
                    put("totalCount", c.totalCount)
                })
            }
        }
    }

    private fun buildSmsThread(ctx: Context, address: String): JsonArray {
        val items = runCatching { SmsLoader.loadMessagesFor(ctx, address) }.getOrDefault(emptyList())
        return buildJsonArray {
            items.forEach { m ->
                add(buildJsonObject {
                    put("body", m.body)
                    put("timestampMs", m.timestampMs)
                    put("incoming", m.incoming)
                    put("read", m.read)
                })
            }
        }
    }

    /**
     * `text.send` is a multi-target relay: paste arbitrary text from a phone
     * keyboard into one of the launcher's input sinks.
     *
     * targets:
     *   - "voice_key"       → save as ElevenLabs API key (sk_* or 32-char hex)
     *   - "voice_custom_id" → save as ElevenLabs voice id (clones / pro voices);
     *                         empty string clears the override
     *   - "openclaw_chat"   → send as a chat message (only when a session is live)
     */
    private fun handleTextSend(
        host: LauncherHost,
        state: LauncherState,
        params: JsonObject?,
    ): JsonElement {
        val target = params.requireString("target")
        val text = params.requireString("text")
        return when (target) {
            "voice_key" -> {
                host.voiceSaveKey(text.trim())
                JsonNull
            }
            "voice_custom_id" -> {
                val v = text.trim()
                if (v.isEmpty()) host.voiceClearCustomVoiceId()
                else host.voiceSaveCustomVoiceId(v)
                JsonNull
            }
            "openclaw_chat" -> {
                if (!state.chatStatus.startsWith("live")) {
                    throw RpcException("openclaw_offline", "openclaw not connected")
                }
                host.openClawSendText(text)
                JsonNull
            }
            else -> throw RpcException("unknown_target", "unknown target: $target")
        }
    }
}

private fun JsonObject?.requireString(key: String): String =
    this?.get(key)?.jsonPrimitive?.contentOrNull
        ?: throw RpcException("missing_param", "missing param: $key")

private fun JsonObject?.requireInt(key: String): Int =
    this?.get(key)?.jsonPrimitive?.int
        ?: throw RpcException("missing_param", "missing param: $key")

private fun JsonObject?.requireBool(key: String): Boolean =
    this?.get(key)?.jsonPrimitive?.boolean
        ?: throw RpcException("missing_param", "missing param: $key")
