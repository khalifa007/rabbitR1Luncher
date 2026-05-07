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
     *   - "voice_key"     → save as ElevenLabs API key (sk_* or 32-char hex)
     *   - "openclaw_chat" → send as a chat message (only when a session is live)
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
