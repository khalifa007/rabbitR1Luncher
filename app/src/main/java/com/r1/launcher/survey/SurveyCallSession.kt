package com.r1.launcher.survey

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One AI-assisted survey call, end-to-end:
 *
 *   1. Connect to OpenAI gpt-realtime-2 with `audio_format = g711_ulaw`.
 *   2. Register with the SIP trunk (REGISTER + digest auth).
 *   3. INVITE the target → 200 OK → ACK → live audio.
 *   4. Inbound RTP G.711 µ-law → directly into the realtime input buffer
 *      (no transcode — both sides speak the same wire format).
 *   5. Realtime emits G.711 audio chunks → buffer and clock out to RTP at
 *      one 160-byte frame every 20 ms.
 *   6. SurveyOrchestrator drives the conversation via tool calls.
 *   7. On end_call (or remote BYE / opt-out / consent denied):
 *        - SIP BYE
 *        - close OpenAI socket
 *        - flush the WAV recorder
 *        - hand the [CallRecord] back to the host for post-call summary + email.
 *
 * Concurrency:
 *   - [uplinkScheduler] paces RTP frames every 20 ms; takes from
 *     [uplinkQueue] (FIFO of µ-law bytes).
 *   - All [Listener] callbacks fire on the main thread.
 *
 * Audio book-keeping: [WavRecorder] writes a 2-channel WAV (left = downlink,
 * right = uplink) into [com.r1.launcher.survey.SurveyStore.audioFile]. The
 * post-call pipeline attaches the file to the summary email.
 */
class SurveyCallSession(
    private val openAiKey: String,
    private val voice: String,
    private val survey: Survey,
    private val contact: Contact,
    private val consentText: String,
    private val sipPrefs: SipCreds,
    private val wavFile: File,
    private val listener: Listener,
) {

    data class SipCreds(
        val host: String,
        val port: Int = 5060,
        val user: String,
        val password: String,
        val fromNumber: String?,
    )

    /**
     * Live-state and terminal callbacks. Mirror the UI's needs (no SipDialer
     * internals exposed). Always invoked on the main thread.
     */
    interface Listener {
        fun onStatus(status: String)
        fun onCallEstablished()
        fun onLiveStateChanged(state: SurveyOrchestrator.LiveState)
        fun onAssistantTextDelta(text: String)
        fun onUserTextFinal(text: String)
        fun onCompleted(
            reason: String,
            consentGranted: Boolean,
            transcript: String,
            answers: Map<String, String>,
            durationMs: Long,
        )
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val wav = WavRecorder(wavFile)
    private var dialer: SipDialer? = null
    private var client: GptRealtimeClient? = null
    private var orchestrator: SurveyOrchestrator? = null

    private val uplinkScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "r1-rtp-uplink").apply { isDaemon = true }
        }
    private var uplinkFuture: ScheduledFuture<*>? = null
    private val uplinkQueue: ArrayDeque<Byte> = ArrayDeque()
    private val uplinkLock = Any()

    @Volatile private var callStartMs: Long = 0L
    @Volatile private var closed = false

    fun start() {
        wav.start()
        // Build the realtime session config with g711_ulaw so we share the
        // exact wire format with RTP — no resampling.
        val sessionConfig = buildSessionConfigG711(
            survey = survey,
            contact = contact,
            consentText = consentText,
            voice = voice,
        )
        val rtClient = GptRealtimeClient.open(openAiKey, sessionConfig, RtCallback())
        client = rtClient
        orchestrator = SurveyOrchestrator(
            survey = survey,
            contact = contact,
            consentText = consentText,
            voice = voice,
            client = rtClient,
            listener = OrchestratorListener(),
        )
        // Now bring up SIP. REGISTER first, INVITE on success.
        dialer = SipDialer(
            host = sipPrefs.host,
            port = sipPrefs.port,
            user = sipPrefs.user,
            password = sipPrefs.password,
            fromNumber = sipPrefs.fromNumber,
            callback = SipCallback(),
        ).also {
            main.post { listener.onStatus("registering") }
            it.startRegister()
        }
    }

    /** Caller-initiated hangup (UI back-press, side-button, etc.). */
    fun hangup() {
        main.post { listener.onStatus("hanging up") }
        dialer?.hangup()
        // Don't tear down yet — wait for the BYE 200 OK or call-terminated.
    }

    /** Force-close everything immediately. */
    fun close(reason: String = "closed") {
        if (closed) return
        closed = true
        uplinkFuture?.cancel(true)
        runCatching { uplinkScheduler.shutdownNow() }
        runCatching { wav.stop() }
        runCatching { dialer?.close() }
        runCatching { client?.close() }
        main.post { listener.onStatus(reason) }
    }

    // ---- SIP wiring ----

    private inner class SipCallback : SipDialer.Callback {
        override fun onRegistered() {
            main.post { listener.onStatus("dialing") }
            dialer?.placeCall(contact.phone)
        }
        override fun onRegistrationFailed(message: String) {
            main.post { listener.onError("SIP register failed: $message") }
            close("register_failed")
            emitCompleted("register_failed")
        }
        override fun onCallProgress(stage: String) {
            main.post { listener.onStatus(stage) }
        }
        override fun onCallEstablished() {
            callStartMs = System.currentTimeMillis()
            main.post {
                listener.onStatus("live")
                listener.onCallEstablished()
            }
            startUplinkPacer()
        }
        override fun onCallTerminated(reason: String) {
            // SIP side ended. Stop the uplink pacer, drain orchestrator state,
            // and fire onCompleted exactly once.
            main.post { listener.onStatus("ended: $reason") }
            uplinkFuture?.cancel(false)
            emitCompleted(reason)
        }
        override fun onDownlinkPayload(payload: ByteArray) {
            // Customer's voice → WAV left channel + OpenAI input buffer.
            wav.appendDownlink(payload)
            client?.sendInputAudio(payload)
        }
        override fun onError(message: String) {
            main.post { listener.onError("SIP: $message") }
        }
    }

    // ---- OpenAI wiring ----

    /** Realtime callbacks land here on the main thread; we forward the audio
     *  bytes to the uplink queue without touching state. */
    private inner class RtCallback : GptRealtimeClient.Callback {
        override fun onConnected() = Unit
        override fun onSessionReady() { orchestrator?.onSessionReady() }
        override fun onUserSpeechStarted() { orchestrator?.onUserSpeechStarted() }
        override fun onUserSpeechStopped() { orchestrator?.onUserSpeechStopped() }
        override fun onAudioOutDelta(pcm: ByteArray) {
            // For g711_ulaw output_audio_format these bytes are raw µ-law,
            // not pcm — the GptRealtimeClient name predates the SIP path.
            orchestrator?.onAudioOutDelta(pcm)
        }
        override fun onAudioOutDone() { orchestrator?.onAudioOutDone() }
        override fun onAssistantTranscriptDelta(text: String) {
            orchestrator?.onAssistantTranscriptDelta(text)
        }
        override fun onUserTranscriptFinal(text: String) {
            orchestrator?.onUserTranscriptFinal(text)
        }
        override fun onToolCall(name: String, callId: String, args: JsonObject) {
            orchestrator?.onToolCall(name, callId, args)
        }
        override fun onResponseDone() { orchestrator?.onResponseDone() }
        override fun onError(message: String) {
            main.post { listener.onError("openai: $message") }
        }
        override fun onDisconnected() {
            // If SIP is still up, this is a fatal mid-call error — tear down.
            if (!closed && dialer != null) {
                dialer?.hangup()
            }
        }
    }

    // ---- Orchestrator wiring ----

    private inner class OrchestratorListener : SurveyOrchestrator.Listener {
        override fun onStateChanged(state: SurveyOrchestrator.LiveState) {
            main.post { listener.onLiveStateChanged(state) }
        }
        override fun onAssistantTextDelta(text: String) {
            main.post { listener.onAssistantTextDelta(text) }
        }
        override fun onUserTextFinal(text: String) {
            main.post { listener.onUserTextFinal(text) }
        }
        override fun onBotAudioChunk(pcm: ByteArray) {
            // Bot µ-law bytes → uplink queue. Pacer pulls 160 bytes per 20 ms.
            // Also captures into the WAV right channel.
            wav.appendUplink(pcm)
            synchronized(uplinkLock) {
                for (b in pcm) uplinkQueue.addLast(b)
            }
        }
        override fun onBotAudioDone() {
            // Insert a small silence tail so the SBC sees the talkburst end.
            synchronized(uplinkLock) {
                repeat(RtpSession.PCMU_FRAME_BYTES) { uplinkQueue.addLast(0xFF.toByte()) }
            }
        }
        override fun onComplete(reason: String, granted: Boolean) {
            // Orchestrator signalled end of conversation. Hang up the SIP call;
            // the SIP side's onCallTerminated callback will fire onCompleted to
            // the listener exactly once.
            dialer?.hangup()
        }
        override fun onError(message: String) {
            main.post { listener.onError("orchestrator: $message") }
        }
    }

    // ---- Uplink pacing ----

    private fun startUplinkPacer() {
        if (uplinkFuture != null) return
        uplinkFuture = uplinkScheduler.scheduleAtFixedRate({
            try {
                val frame = ByteArray(RtpSession.PCMU_FRAME_BYTES)
                synchronized(uplinkLock) {
                    for (i in 0 until RtpSession.PCMU_FRAME_BYTES) {
                        frame[i] = if (uplinkQueue.isNotEmpty())
                            uplinkQueue.removeFirst()
                        else
                            0xFF.toByte()  // µ-law silence
                    }
                }
                dialer?.sendUplinkPayload(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "uplink pacer: ${t.message}")
            }
        }, 0L, RtpSession.PCMU_FRAME_MS, TimeUnit.MILLISECONDS)
    }

    private var completedFired = false
    private fun emitCompleted(reason: String) {
        if (completedFired) return
        completedFired = true
        val orch = orchestrator
        val transcript = orch?.renderTranscript().orEmpty()
        val answers = orch?.state?.answers?.toMap().orEmpty()
        val granted = orch?.state?.consentGranted ?: false
        val durationMs = if (callStartMs > 0) System.currentTimeMillis() - callStartMs else 0L
        main.post { listener.onCompleted(reason, granted, transcript, answers, durationMs) }
        close(reason)
    }

    companion object {
        private const val TAG = "SurveyCallSession"

        /** Variant of [SurveyOrchestrator.buildSessionConfig] that selects
         *  G.711 µ-law for both directions so we don't have to resample.
         *  The customer's voice goes from SIP RTP straight into OpenAI as
         *  bytes, and the assistant's voice flows out the same way. */
        fun buildSessionConfigG711(
            survey: Survey,
            contact: Contact,
            consentText: String,
            voice: String,
        ): JsonObject {
            val base = SurveyOrchestrator.buildSessionConfig(survey, contact, consentText, voice)
            // Override the two audio_format fields. JsonObject is immutable so
            // copy + overwrite via builder.
            return buildJsonObject {
                base.forEach { (k, v) ->
                    if (k != "input_audio_format" && k != "output_audio_format") put(k, v)
                }
                put("input_audio_format", "g711_ulaw")
                put("output_audio_format", "g711_ulaw")
            }
        }
    }
}
