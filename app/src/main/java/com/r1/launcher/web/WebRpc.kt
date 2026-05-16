package com.r1.launcher.web

import android.content.Context
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import com.r1.launcher.messages.SmsLoader
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
        "hermes.send" -> {
            if (state.hermesServerUrl.isBlank()) {
                throw RpcException("hermes_unconfigured", "hermes server url not set")
            }
            host.hermesSendText(params.requireString("text")); JsonNull
        }
        "hermes.clear" -> {
            host.hermesClearHistory(); JsonNull
        }
        "hermes.history" -> buildJsonObject {
            put("busy", state.hermesBusy)
            put("status", state.hermesStatus)
            put("streaming", state.hermesStreamingText)
            put("messages", buildJsonArray {
                state.hermesMessages.toList().forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("text", m.text)
                    })
                }
            })
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

        else -> throw RpcException("unknown_method", "unknown method: $method")
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
        put("hermes", buildJsonObject {
            put("status", state.hermesStatus)
            put("hasConfig", state.hermesServerUrl.isNotBlank())
            put("model", state.hermesModel)
            put("messageCount", state.hermesMessages.size)
            put("busy", state.hermesBusy)
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
            "hermes_chat" -> {
                if (state.hermesServerUrl.isBlank()) {
                    throw RpcException("hermes_unconfigured", "hermes server url not set")
                }
                host.hermesSendText(text)
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
