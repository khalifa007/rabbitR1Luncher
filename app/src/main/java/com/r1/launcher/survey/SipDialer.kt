package com.r1.launcher.survey

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Kotlin SIP-over-UDP dialer: REGISTER + INVITE + ACK + BYE happy path.
 * Designed against Twilio, Plivo, Voip.ms and Asterisk; uses MD5 digest auth
 * (RFC 2617) and codes against RFC 3261 / 3550 / 3551.
 *
 * Lifecycle:
 *   val d = SipDialer(host, user, password, fromNumber, callback)
 *   d.startRegister()
 *       …   onRegistered → can place calls
 *   d.placeCall("+15551234567")
 *       …   onCallEstablished → audio is up
 *       …   each RTP payload → callback.onDownlinkPayload(bytes)
 *       …   callback emits uplink: d.sendUplinkPayload(bytes) per frame
 *   d.hangup()    or remote BYE → callback.onCallTerminated(reason)
 *   d.close()
 *
 * **Threading**: one daemon receiver thread handles inbound UDP; one
 * single-thread executor handles state transitions. Caller callbacks fire on
 * the executor — keep them short and non-blocking.
 *
 * **NAT**: outbound only. We rely on the SIP provider's SBC to support
 * symmetric-RTP / `;rport` so responses land at the same hole the request
 * opened. ICE / STUN not implemented.
 */
class SipDialer(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val fromNumber: String?,
    private val callback: Callback,
) {

    interface Callback {
        fun onRegistered()
        fun onRegistrationFailed(message: String)
        fun onCallProgress(stage: String)          // "dialing" | "ringing"
        fun onCallEstablished()
        fun onCallTerminated(reason: String)
        /** Inbound G.711 µ-law payload (160 bytes per 20 ms at 8 kHz). */
        fun onDownlinkPayload(payload: ByteArray)
        fun onError(message: String)
    }

    private val rng = SecureRandom()
    private val socket: DatagramSocket = DatagramSocket()
    private val rtpSocket: DatagramSocket = DatagramSocket()
    private val remoteAddress: InetAddress = InetAddress.getByName(host)

    private val running = AtomicBoolean(false)
    private val sipRxExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "r1-sip-rx").apply { isDaemon = true }
    }
    private val stateExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "r1-sip-state").apply { isDaemon = true }
    }
    private val uplinkExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "r1-sip-tx").apply { isDaemon = true }
    }

    // Local identity & transport.
    private val localAddress: String by lazy { discoverLocalAddress() }
    private val localPort: Int get() = socket.localPort
    private val localRtpPort: Int get() = rtpSocket.localPort
    private val tag: String = randomToken(8)
    private val branchSeed: String = randomToken(12)
    private val callIdSeed: String = "${UUID.randomUUID()}@$localAddress"
    private val userAgent: String = "R1Surveyor/1.0"

    // REGISTER state
    @Volatile private var registered: Boolean = false
    @Volatile private var lastRegisterChallenge: DigestAuth.Challenge? = null
    @Volatile private var registerCseq: Int = 1
    @Volatile private var registerCallId: String = "reg-${randomToken(10)}-$callIdSeed"
    @Volatile private var registerBranch: String = ""

    // Per-call state.
    private data class CallState(
        val callId: String,
        val branch: String,
        val fromTag: String,
        var toTag: String? = null,
        val cseq: Int,
        val target: String,
        val requestUri: String,
        var rtp: RtpSession? = null,
        var remoteRtpAddress: InetAddress? = null,
        var remoteRtpPort: Int = 0,
        var contact: String? = null,
        var routeSet: List<String> = emptyList(),
        var lastInviteAuthorization: String? = null,
        var lastInviteProxyAuthorization: String? = null,
        var inviteCseq: Int = 1,
        var established: Boolean = false,
        var terminated: Boolean = false,
    )
    @Volatile private var call: CallState? = null

    fun startRegister() {
        running.set(true)
        // Bind UDP, kick the receive loop.
        socket.soTimeout = 250
        sipRxExec.execute { receiveLoop() }
        stateExec.execute { sendRegister(null) }
    }

    fun placeCall(targetNumber: String) {
        stateExec.execute {
            if (!registered) {
                callback.onError("not registered; cannot place call")
                return@execute
            }
            val cleaned = targetNumber.trim()
            val ruri = if (cleaned.startsWith("sip:")) cleaned else "sip:$cleaned@$host"
            val st = CallState(
                callId = "call-${randomToken(10)}-$callIdSeed",
                branch = "z9hG4bK${randomToken(10)}",
                fromTag = randomToken(8),
                cseq = 1,
                target = cleaned,
                requestUri = ruri,
                inviteCseq = 1,
            )
            call = st
            sendInvite(st, null, null)
        }
    }

    fun hangup() {
        stateExec.execute {
            val st = call ?: return@execute
            if (st.terminated) return@execute
            if (st.established) sendBye(st)
            else sendCancel(st)
            st.terminated = true
        }
    }

    /** Send one uplink G.711 µ-law payload (160 bytes for 20 ms). The
     *  RtpSession does framing — caller just paces the byte stream. */
    fun sendUplinkPayload(payload: ByteArray) {
        val rtp = call?.rtp ?: return
        uplinkExec.execute { rtp.sendPayload(payload) }
    }

    fun close() {
        running.set(false)
        try { call?.rtp?.stop() } catch (_: Throwable) {}
        try { socket.close() } catch (_: Throwable) {}
        try { rtpSocket.close() } catch (_: Throwable) {}
        runCatching { sipRxExec.shutdownNow() }
        runCatching { stateExec.shutdownNow() }
        runCatching { uplinkExec.shutdownNow() }
    }

    // ---- REGISTER ----

    private fun sendRegister(authValue: String?) {
        registerBranch = "z9hG4bK${randomToken(10)}"
        val regUri = "sip:$host"
        val msg = SipMessageBuilder(method = "REGISTER", requestUri = regUri)
            .add("Via", "SIP/2.0/UDP $localAddress:$localPort;rport;branch=$registerBranch")
            .add("Max-Forwards", "70")
            .add("From", "<sip:$user@$host>;tag=$tag")
            .add("To", "<sip:$user@$host>")
            .add("Call-ID", registerCallId)
            .add("CSeq", "$registerCseq REGISTER")
            .add("Contact", "<sip:$user@$localAddress:$localPort;transport=udp>")
            .add("Expires", REGISTER_EXPIRES.toString())
            .add("User-Agent", userAgent)
            .add("Content-Length", "0")
            .also { if (authValue != null) it.add("Authorization", authValue) }
            .build()
        sendUdp(msg)
    }

    // ---- INVITE ----

    private fun sendInvite(
        st: CallState,
        wwwAuth: String?,
        proxyAuth: String?,
    ) {
        // Bump cseq for re-INVITE with credentials. RFC 3261 §22.2 requires it.
        if (wwwAuth != null || proxyAuth != null) st.inviteCseq++
        st.lastInviteAuthorization = wwwAuth
        st.lastInviteProxyAuthorization = proxyAuth
        val sdp = SdpBuilder.buildOffer(localAddress, localRtpPort, rng.nextLong() and 0x7FFFFFFFL)
        val from = if (fromNumber.isNullOrBlank()) "<sip:$user@$host>"
                   else "\"R1Surveyor\" <sip:$fromNumber@$host>"
        val msg = SipMessageBuilder(method = "INVITE", requestUri = st.requestUri)
            .add("Via", "SIP/2.0/UDP $localAddress:$localPort;rport;branch=${st.branch}")
            .add("Max-Forwards", "70")
            .add("From", "$from;tag=${st.fromTag}")
            .add("To", "<${st.requestUri}>")
            .add("Call-ID", st.callId)
            .add("CSeq", "${st.inviteCseq} INVITE")
            .add("Contact", "<sip:$user@$localAddress:$localPort;transport=udp>")
            .add("User-Agent", userAgent)
            .add("Allow", "INVITE, ACK, CANCEL, BYE, OPTIONS")
            .add("Content-Type", "application/sdp")
            .add("Content-Length", sdp.toByteArray(Charsets.ISO_8859_1).size.toString())
            .also {
                if (wwwAuth != null) it.add("Authorization", wwwAuth)
                if (proxyAuth != null) it.add("Proxy-Authorization", proxyAuth)
            }
            .build()
        val msgWithBody = SipMessage(
            method = msg.method, requestUri = msg.requestUri,
            statusCode = msg.statusCode, statusText = msg.statusText,
            headers = msg.headers, headerOrder = msg.headerOrder,
            body = sdp,
        )
        sendUdp(msgWithBody)
        callback.onCallProgress("dialing")
    }

    private fun sendAck(st: CallState, to: String) {
        val ackBranch = "z9hG4bK${randomToken(10)}"
        val target = st.contact ?: st.requestUri
        val ack = SipMessageBuilder(method = "ACK", requestUri = target)
            .add("Via", "SIP/2.0/UDP $localAddress:$localPort;rport;branch=$ackBranch")
            .add("Max-Forwards", "70")
            .add("From", "<sip:$user@$host>;tag=${st.fromTag}")
            .add("To", to)
            .add("Call-ID", st.callId)
            .add("CSeq", "${st.inviteCseq} ACK")
            .add("User-Agent", userAgent)
            .add("Content-Length", "0")
            .build()
        sendUdp(ack)
    }

    private fun sendCancel(st: CallState) {
        // CANCEL uses the same branch as the INVITE per RFC 3261 §9.1.
        val cancel = SipMessageBuilder(method = "CANCEL", requestUri = st.requestUri)
            .add("Via", "SIP/2.0/UDP $localAddress:$localPort;rport;branch=${st.branch}")
            .add("Max-Forwards", "70")
            .add("From", "<sip:$user@$host>;tag=${st.fromTag}")
            .add("To", "<${st.requestUri}>")
            .add("Call-ID", st.callId)
            .add("CSeq", "${st.inviteCseq} CANCEL")
            .add("User-Agent", userAgent)
            .add("Content-Length", "0")
            .build()
        sendUdp(cancel)
    }

    private fun sendBye(st: CallState) {
        val target = st.contact ?: st.requestUri
        val byeBranch = "z9hG4bK${randomToken(10)}"
        val to = st.toTag?.let { "<${st.requestUri}>;tag=$it" } ?: "<${st.requestUri}>"
        val bye = SipMessageBuilder(method = "BYE", requestUri = target)
            .add("Via", "SIP/2.0/UDP $localAddress:$localPort;rport;branch=$byeBranch")
            .add("Max-Forwards", "70")
            .add("From", "<sip:$user@$host>;tag=${st.fromTag}")
            .add("To", to)
            .add("Call-ID", st.callId)
            .add("CSeq", "${st.inviteCseq + 1} BYE")
            .add("User-Agent", userAgent)
            .add("Content-Length", "0")
            .build()
        sendUdp(bye)
    }

    // ---- Inbound dispatch ----

    private fun receiveLoop() {
        val buf = ByteArray(8192)
        val pkt = DatagramPacket(buf, buf.size)
        while (running.get() && !socket.isClosed) {
            try {
                socket.receive(pkt)
                val msg = SipMessage.parse(buf, pkt.length) ?: continue
                stateExec.execute { onMessage(msg, pkt.address, pkt.port) }
            } catch (_: java.net.SocketTimeoutException) {
                // wakeup so we can re-check `running`
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "sip recv: ${t.message}")
            }
        }
    }

    private fun onMessage(msg: SipMessage, fromAddr: InetAddress, fromPort: Int) {
        if (msg.isRequest) {
            handleRequest(msg, fromAddr, fromPort)
        } else {
            handleResponse(msg)
        }
    }

    private fun handleRequest(msg: SipMessage, fromAddr: InetAddress, fromPort: Int) {
        when (msg.method) {
            "BYE" -> {
                val st = call
                if (st != null && !st.terminated) {
                    st.terminated = true
                    respondToInDialogRequest(msg, 200, "OK", fromAddr, fromPort)
                    teardownCall("remote_bye")
                } else {
                    respondToInDialogRequest(msg, 481, "Call/Transaction Does Not Exist", fromAddr, fromPort)
                }
            }
            "OPTIONS", "INFO" -> {
                respondToInDialogRequest(msg, 200, "OK", fromAddr, fromPort)
            }
            "ACK" -> { /* mid-dialog ACKs ignored */ }
            "CANCEL" -> {
                respondToInDialogRequest(msg, 200, "OK", fromAddr, fromPort)
            }
            else -> {
                // Unsupported method.
                respondToInDialogRequest(msg, 405, "Method Not Allowed", fromAddr, fromPort)
            }
        }
    }

    private fun respondToInDialogRequest(
        req: SipMessage,
        code: Int,
        text: String,
        fromAddr: InetAddress,
        fromPort: Int,
    ) {
        val resp = SipMessageBuilder(statusCode = code, statusText = text)
        // Copy Via, From, To, Call-ID, CSeq verbatim.
        listOf("via", "from", "to", "call-id", "cseq").forEach { h ->
            req.headersAll(h).forEach { v ->
                resp.add(h.replaceFirstChar { it.uppercaseChar() }, v)
            }
        }
        resp.add("Content-Length", "0")
        try {
            val bytes = resp.build().encode()
            socket.send(DatagramPacket(bytes, bytes.size, fromAddr, fromPort))
        } catch (t: Throwable) {
            Log.w(TAG, "respond failed: ${t.message}")
        }
    }

    private fun handleResponse(msg: SipMessage) {
        val cseqHeader = msg.header("cseq") ?: return
        val cseqTokens = cseqHeader.split(' ')
        val cseqMethod = cseqTokens.getOrNull(1)?.uppercase() ?: return
        when (cseqMethod) {
            "REGISTER" -> handleRegisterResponse(msg)
            "INVITE" -> handleInviteResponse(msg)
            "BYE" -> {
                // 2xx to BYE — done.
                if (msg.statusCode in 200..299) teardownCall("local_bye")
            }
            "CANCEL" -> {
                // ignore — 200 to CANCEL just acknowledges; the INVITE's 487
                // is what tears the dialog down.
            }
        }
    }

    private fun handleRegisterResponse(msg: SipMessage) {
        when (msg.statusCode) {
            in 200..299 -> {
                registered = true
                registerCseq++
                callback.onRegistered()
            }
            401, 407 -> {
                val authHeader = if (msg.statusCode == 401)
                    msg.header("www-authenticate")
                else msg.header("proxy-authenticate")
                val ch = authHeader?.let { DigestAuth.parseChallenge(it) }
                if (ch == null) {
                    callback.onRegistrationFailed("malformed challenge")
                    return
                }
                if (lastRegisterChallenge?.nonce == ch.nonce) {
                    callback.onRegistrationFailed("authentication rejected")
                    return
                }
                lastRegisterChallenge = ch
                registerCseq++
                val authValue = DigestAuth.buildAuthorizationHeader(
                    challenge = ch,
                    method = "REGISTER",
                    uri = "sip:$host",
                    username = user,
                    password = password,
                )
                sendRegister(authValue)
            }
            else -> callback.onRegistrationFailed("REGISTER ${msg.statusCode} ${msg.statusText.orEmpty()}")
        }
    }

    private fun handleInviteResponse(msg: SipMessage) {
        val st = call ?: return
        if (st.terminated) return
        val code = msg.statusCode
        when {
            code in 100..199 -> {
                if (code == 180) callback.onCallProgress("ringing")
                msg.header("to")?.let { extractTagInto(it, st) }
            }
            code in 200..299 -> {
                st.toTag = msg.header("to")?.let { SipMessage.extractParam(it, "tag") } ?: st.toTag
                // Save Contact for in-dialog targets.
                msg.header("contact")?.let { st.contact = parseContact(it) }
                // Parse remote SDP, bring up RTP.
                val sdp = SdpBuilder.parse(msg.body)
                if (sdp == null) {
                    callback.onError("missing SDP in 200 OK")
                    hangup()
                    return
                }
                st.remoteRtpAddress = InetAddress.getByName(sdp.remoteAddress)
                st.remoteRtpPort = sdp.remoteRtpPort
                // ACK the 200 OK *before* opening the media so the dialog is
                // confirmed when the first RTP packet arrives.
                val toHeader = msg.header("to") ?: "<${st.requestUri}>"
                sendAck(st, toHeader)
                openMedia(st)
                st.established = true
                callback.onCallEstablished()
            }
            code == 401 || code == 407 -> {
                val authHeader = if (code == 401) msg.header("www-authenticate")
                                 else msg.header("proxy-authenticate")
                val ch = authHeader?.let { DigestAuth.parseChallenge(it) }
                if (ch == null) {
                    callback.onError("INVITE: malformed auth challenge")
                    hangup(); return
                }
                // ACK the 401/407 per RFC 3261 §17.1.1.3 — same branch as INVITE.
                ackErrorResponse(st, msg)
                val authValue = DigestAuth.buildAuthorizationHeader(
                    challenge = ch,
                    method = "INVITE",
                    uri = st.requestUri,
                    username = user,
                    password = password,
                )
                if (code == 401) sendInvite(st, authValue, st.lastInviteProxyAuthorization)
                else sendInvite(st, st.lastInviteAuthorization, authValue)
            }
            code in 300..699 -> {
                ackErrorResponse(st, msg)
                val reason = "INVITE $code ${msg.statusText.orEmpty()}"
                st.terminated = true
                callback.onCallTerminated(reason)
                teardownCallSocketsOnly()
            }
        }
    }

    /** ACK for a non-2xx INVITE response — same branch as the original INVITE
     *  per RFC 3261 §17.1.1.3. We use the response's To header (tag included). */
    private fun ackErrorResponse(st: CallState, response: SipMessage) {
        val to = response.header("to") ?: "<${st.requestUri}>"
        val ack = SipMessageBuilder(method = "ACK", requestUri = st.requestUri)
            .add("Via", "SIP/2.0/UDP $localAddress:$localPort;rport;branch=${st.branch}")
            .add("Max-Forwards", "70")
            .add("From", "<sip:$user@$host>;tag=${st.fromTag}")
            .add("To", to)
            .add("Call-ID", st.callId)
            .add("CSeq", "${st.inviteCseq} ACK")
            .add("Content-Length", "0")
            .build()
        sendUdp(ack)
    }

    private fun extractTagInto(toHeader: String, st: CallState) {
        SipMessage.extractParam(toHeader, "tag")?.let { st.toTag = it }
    }

    private fun openMedia(st: CallState) {
        val addr = st.remoteRtpAddress ?: return
        rtpSocket.soTimeout = 250
        val rtp = RtpSession(
            socket = rtpSocket,
            remoteAddress = addr,
            remotePort = st.remoteRtpPort,
            onPayload = { p -> callback.onDownlinkPayload(p) },
            onError = { t -> Log.w(TAG, "rtp: ${t.message}") },
        )
        st.rtp = rtp
        rtp.start()
        // Symmetric-RTP NAT prime: fire a 20 ms silence packet so the SBC's
        // mapping resolves outbound-first. Bot audio replaces it within a
        // few hundred ms.
        rtp.markNextTalkburst()
        rtp.sendPayload(ByteArray(RtpSession.PCMU_FRAME_BYTES) { 0xFF.toByte() })
    }

    // ---- Helpers ----

    private fun teardownCall(reason: String) {
        val st = call ?: return
        try { st.rtp?.stop() } catch (_: Throwable) {}
        st.terminated = true
        callback.onCallTerminated(reason)
    }

    /** Like [teardownCall] without the public callback — used after we've
     *  already reported the terminating reason. */
    private fun teardownCallSocketsOnly() {
        val st = call ?: return
        try { st.rtp?.stop() } catch (_: Throwable) {}
    }

    private fun sendUdp(msg: SipMessage) {
        try {
            val bytes = msg.encode()
            val dst = InetSocketAddress(remoteAddress, port)
            socket.send(DatagramPacket(bytes, bytes.size, dst))
        } catch (t: Throwable) {
            callback.onError("sip send failed: ${t.message}")
        }
    }

    private fun parseContact(contactHeader: String): String? {
        // <sip:foo@bar:port> OR "name" <sip:foo@bar>;params
        val lt = contactHeader.indexOf('<')
        val gt = contactHeader.indexOf('>')
        if (lt >= 0 && gt > lt) return contactHeader.substring(lt + 1, gt)
        return contactHeader.substringBefore(';').trim().ifBlank { null }
    }

    private fun discoverLocalAddress(): String = runCatching {
        // Connect a UDP socket toward the SIP host — kernel picks the right
        // outbound interface; we read its local IP back.
        DatagramSocket().use {
            it.connect(remoteAddress, port)
            it.localAddress.hostAddress
        }
    }.getOrElse {
        "0.0.0.0"
    }

    private fun randomToken(nibbles: Int): String {
        val bytes = ByteArray((nibbles + 1) / 2)
        rng.nextBytes(bytes)
        val sb = StringBuilder(nibbles)
        for (b in bytes) {
            sb.append("%02x".format(b.toInt() and 0xFF))
        }
        return sb.substring(0, nibbles)
    }

    companion object {
        private const val TAG = "SurveySip"
        private const val REGISTER_EXPIRES = 600
    }
}
