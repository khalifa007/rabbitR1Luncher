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
        "state.snapshot" -> buildSnapshot(state)

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
        "openai.set" -> {
            host.openClawSaveOpenaiKey(params.requireString("key")); JsonNull
        }

        else -> throw RpcException("unknown_method", "unknown method: $method")
    }

    fun buildSnapshot(state: LauncherState): JsonObject = buildJsonObject {
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
            put("hasOpenAiKey", state.chatHasOpenaiKey)
            put("openAiKeyTail", state.chatOpenaiKeyTail)
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
     *   - "openai_key"     → save as Whisper key (sk-* validated)
     *   - "openclaw_chat"  → send as a chat message (only when a session is live)
     */
    private fun handleTextSend(
        host: LauncherHost,
        state: LauncherState,
        params: JsonObject?,
    ): JsonElement {
        val target = params.requireString("target")
        val text = params.requireString("text")
        return when (target) {
            "openai_key" -> {
                val k = text.trim()
                if (!k.startsWith("sk-") || k.length < 20) {
                    throw RpcException("bad_key", "not a valid openai key")
                }
                host.openClawSaveOpenaiKey(k)
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
