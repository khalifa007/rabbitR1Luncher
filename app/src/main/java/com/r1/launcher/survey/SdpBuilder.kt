package com.r1.launcher.survey

/**
 * Single-purpose SDP builder/parser. We only ever offer/answer one audio
 * stream, G.711 µ-law (RTP payload type 0) at 8 kHz mono — matches OpenAI's
 * realtime API's `g711_ulaw` audio_format exactly, so we don't have to
 * transcode anything in the bridge.
 *
 * Offer template (RFC 4566 minus the inessential):
 *   v=0
 *   o=<user> <id> <ver> IN IP4 <localAddr>
 *   s=R1Surveyor
 *   c=IN IP4 <localAddr>
 *   t=0 0
 *   m=audio <localRtpPort> RTP/AVP 0 101
 *   a=rtpmap:0 PCMU/8000
 *   a=rtpmap:101 telephone-event/8000     // DTMF, sent only, never received
 *   a=fmtp:101 0-15
 *   a=ptime:20
 *   a=sendrecv
 */
data class SdpDescription(
    val remoteAddress: String,
    val remoteRtpPort: Int,
    /** True if peer's `a=sendonly` / `a=recvonly` would gate one direction.
     *  We treat sendrecv (default) and sendonly (we can still send) as fine. */
    val recvAllowed: Boolean = true,
    val sendAllowed: Boolean = true,
)

object SdpBuilder {

    fun buildOffer(localAddress: String, localRtpPort: Int, sessionId: Long): String =
        buildString {
            append("v=0\r\n")
            append("o=- $sessionId $sessionId IN IP4 $localAddress\r\n")
            append("s=R1Surveyor\r\n")
            append("c=IN IP4 $localAddress\r\n")
            append("t=0 0\r\n")
            append("m=audio $localRtpPort RTP/AVP 0 101\r\n")
            append("a=rtpmap:0 PCMU/8000\r\n")
            append("a=rtpmap:101 telephone-event/8000\r\n")
            append("a=fmtp:101 0-15\r\n")
            append("a=ptime:20\r\n")
            append("a=sendrecv\r\n")
        }

    /** Parse a remote SDP. Looks for the first audio m-line and returns the
     *  c-line address (or session-level c-line if no media-level one). */
    fun parse(sdp: String): SdpDescription? {
        var sessionAddress: String? = null
        var mediaAddress: String? = null
        var mediaPort: Int? = null
        var inAudio = false
        var sendAllowed = true
        var recvAllowed = true
        for (line in sdp.lineSequence().map { it.trimEnd('\r') }) {
            if (line.isEmpty()) continue
            when {
                line.startsWith("c=IN IP4 ") -> {
                    val addr = line.removePrefix("c=IN IP4 ").trim()
                    val onlyAddr = addr.substringBefore('/').trim()
                    if (inAudio) mediaAddress = onlyAddr
                    else sessionAddress = onlyAddr
                }
                line.startsWith("m=audio ") -> {
                    inAudio = true
                    val tokens = line.removePrefix("m=audio ").split(' ')
                    mediaPort = tokens.firstOrNull()?.toIntOrNull()
                }
                line.startsWith("m=") && !line.startsWith("m=audio") -> {
                    inAudio = false
                }
                line == "a=sendonly" && inAudio -> { recvAllowed = false }
                line == "a=recvonly" && inAudio -> { sendAllowed = false }
                line == "a=inactive" && inAudio -> { sendAllowed = false; recvAllowed = false }
            }
        }
        val addr = mediaAddress ?: sessionAddress ?: return null
        val port = mediaPort ?: return null
        if (port <= 0) return null
        return SdpDescription(
            remoteAddress = addr,
            remoteRtpPort = port,
            recvAllowed = recvAllowed,
            sendAllowed = sendAllowed,
        )
    }
}
