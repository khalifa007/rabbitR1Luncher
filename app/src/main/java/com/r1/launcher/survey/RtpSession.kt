package com.r1.launcher.survey

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bare-minimum RTP/UDP session, hard-wired for G.711 µ-law (RFC 3551 §4.5.14):
 *   - payload type 0 (PCMU)
 *   - 8 kHz clock
 *   - 20 ms framing → 160 samples / 160 bytes per packet
 *   - 50 packets/s
 *
 * Header (RFC 3550 §5.1):
 *   bits 0–1  V=2  (always 2)
 *   bit  2    P    (padding) — always 0
 *   bit  3    X    (extension) — always 0
 *   bits 4–7  CC=0
 *   bit  8    M    (marker) — set on the first packet of each talk-burst
 *   bits 9–15 PT=0 (PCMU)
 *   16–31     sequence number (random initial)
 *   32–63     timestamp        (random initial, +160 per packet)
 *   64–95     SSRC             (random)
 *
 * Background receive thread loops on `receive()` until [stop] is called.
 * Send is fire-and-forget from any thread (UDP is non-blocking enough).
 *
 * NAT note: we send our first uplink packet immediately after the SIP 200 OK /
 * ACK exchange (even if it's a silence frame), so the SBC's symmetric-RTP
 * heuristic opens the mapping in the same direction. Without this, providers
 * that perform "symmetric RTP" can't deliver the customer's audio back to us.
 */
class RtpSession(
    private val socket: DatagramSocket,
    private val remoteAddress: InetAddress,
    private val remotePort: Int,
    private val onPayload: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var rxThread: Thread? = null

    private val rng = SecureRandom()
    private var seq: Int = rng.nextInt() and 0xFFFF
    private var timestamp: Long = (rng.nextLong() and 0xFFFFFFFFL)
    private val ssrc: Int = rng.nextInt()
    @Volatile private var markerNext: Boolean = true

    fun start() {
        if (!running.compareAndSet(false, true)) return
        rxThread = Thread({
            // Receive loop. socket.soTimeout governs whether this thread can
            // see [stop()] in a reasonable time — caller sets it before us.
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    socket.receive(pkt)
                    if (pkt.length < 12) continue
                    // RFC 3550 §5.3.1: account for CSRC count + optional ext.
                    val cc = buf[0].toInt() and 0x0F
                    var headerLen = 12 + cc * 4
                    val hasExt = (buf[0].toInt() and 0x10) != 0
                    if (hasExt && headerLen + 4 <= pkt.length) {
                        val extWords = ((buf[headerLen + 2].toInt() and 0xFF) shl 8) or
                            (buf[headerLen + 3].toInt() and 0xFF)
                        headerLen += 4 + extWords * 4
                    }
                    if (headerLen >= pkt.length) continue
                    val payload = buf.copyOfRange(headerLen, pkt.length)
                    onPayload(payload)
                } catch (t: java.net.SocketTimeoutException) {
                    // Expected — used to wake up so we can check [running].
                } catch (t: java.net.SocketException) {
                    if (running.get()) onError(t)
                    return@Thread
                } catch (t: Throwable) {
                    if (running.get()) onError(t)
                }
            }
        }, "r1-rtp-rx").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Send one G.711 µ-law payload of any length. Caller is responsible for
     * pacing (20 ms cadence = 160-byte packets). The RtpSession just frames
     * and writes.
     *
     * If [advanceTimestamp] is true (default) the timestamp moves by [payload]
     * length — correct for PCMU's 1-sample-per-byte rate.
     */
    fun sendPayload(payload: ByteArray, advanceTimestamp: Boolean = true) {
        if (!running.get() || payload.isEmpty()) return
        val len = payload.size
        val pkt = ByteArray(12 + len)
        pkt[0] = 0x80.toByte()                                 // V=2, P=0, X=0, CC=0
        pkt[1] = if (markerNext) 0x80.toByte() else 0x00       // M + PT=0
        markerNext = false
        pkt[2] = ((seq ushr 8) and 0xFF).toByte()
        pkt[3] = (seq and 0xFF).toByte()
        pkt[4] = ((timestamp ushr 24) and 0xFFL).toByte()
        pkt[5] = ((timestamp ushr 16) and 0xFFL).toByte()
        pkt[6] = ((timestamp ushr 8) and 0xFFL).toByte()
        pkt[7] = (timestamp and 0xFFL).toByte()
        pkt[8] = ((ssrc ushr 24) and 0xFF).toByte()
        pkt[9] = ((ssrc ushr 16) and 0xFF).toByte()
        pkt[10] = ((ssrc ushr 8) and 0xFF).toByte()
        pkt[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, 12, len)
        try {
            socket.send(DatagramPacket(pkt, pkt.size, remoteAddress, remotePort))
        } catch (t: Throwable) {
            if (running.get()) {
                Log.w(TAG, "rtp send failed: ${t.message}")
                onError(t)
            }
        }
        seq = (seq + 1) and 0xFFFF
        if (advanceTimestamp) timestamp = (timestamp + len) and 0xFFFFFFFFL
    }

    /** Mark the next outbound packet as a talk-burst start. Optional — most
     *  SBCs ignore the M bit on receive, but it's correct per spec. */
    fun markNextTalkburst() {
        markerNext = true
    }

    fun stop() {
        running.set(false)
        // Don't close the socket here — SipDialer owns it.
        rxThread?.interrupt()
        rxThread = null
    }

    companion object {
        private const val TAG = "SurveyRtp"
        /** PCMU = 8 kHz mono, 8 bit/sample, 20 ms framing → 160 bytes per packet. */
        const val PCMU_FRAME_BYTES = 160
        const val PCMU_FRAME_MS = 20L
    }
}
