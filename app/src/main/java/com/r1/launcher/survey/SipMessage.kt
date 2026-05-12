package com.r1.launcher.survey

/**
 * Tiny SIP-over-UDP message parser/builder. Covers exactly the wire that the
 * Survey Call Bot exchanges with a SIP trunk: REGISTER / INVITE / ACK / BYE /
 * CANCEL on the request side; 1xx / 2xx / 401 / 407 / 4xx-5xx on the response
 * side.
 *
 * Wire format (RFC 3261):
 *   <start-line>\r\n
 *   Header-Name: value\r\n
 *   …
 *   \r\n
 *   <body>
 *
 * No multipart bodies, no compact header forms (we emit long names; we accept
 * the standard compact aliases on parse so providers like Twilio that respond
 * with "i:" / "m:" / "t:" still round-trip).
 */
data class SipMessage(
    /** Either "REGISTER", "INVITE", etc. for requests; null for responses. */
    val method: String? = null,
    /** The request-URI for requests, the reason phrase for responses. */
    val requestUri: String? = null,
    /** Status code for responses (100, 180, 200, 401…). 0 for requests. */
    val statusCode: Int = 0,
    val statusText: String? = null,
    /** Lower-case header name → list of values. Preserves order via [headerOrder]. */
    val headers: Map<String, List<String>> = emptyMap(),
    val headerOrder: List<String> = emptyList(),
    val body: String = "",
) {
    val isResponse: Boolean get() = method == null
    val isRequest: Boolean get() = method != null

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    fun headersAll(name: String): List<String> = headers[name.lowercase()] ?: emptyList()

    /** Pull a single token like `branch=…` out of a comma/semicolon-separated
     *  parameter list (Via header etc.). */
    fun headerParam(name: String, paramName: String): String? {
        val raw = header(name) ?: return null
        return extractParam(raw, paramName)
    }

    fun encode(): ByteArray {
        val sb = StringBuilder()
        if (isRequest) {
            sb.append("$method $requestUri SIP/2.0\r\n")
        } else {
            sb.append("SIP/2.0 $statusCode ${statusText ?: ""}\r\n")
        }
        // Emit headers in original order so responses look natural.
        val emitted = HashSet<String>()
        for (name in headerOrder) {
            val key = name.lowercase()
            if (key in emitted) continue
            emitted.add(key)
            val values = headers[key] ?: continue
            for (v in values) sb.append("${name}: $v\r\n")
        }
        sb.append("\r\n")
        sb.append(body)
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        // RFC 3261 §7.3.3 — accept these compact forms; we don't emit them.
        private val COMPACT_ALIASES: Map<String, String> = mapOf(
            "i" to "call-id",
            "m" to "contact",
            "e" to "content-encoding",
            "l" to "content-length",
            "c" to "content-type",
            "f" to "from",
            "s" to "subject",
            "k" to "supported",
            "t" to "to",
            "v" to "via",
            "u" to "allow-events",
            "o" to "event",
            "r" to "refer-to",
            "b" to "referred-by",
            "a" to "accept-contact",
            "j" to "reject-contact",
            "d" to "request-disposition",
            "x" to "session-expires",
            "n" to "identity-info",
            "y" to "identity",
        )

        fun parse(bytes: ByteArray, len: Int = bytes.size): SipMessage? {
            val text = String(bytes, 0, len, Charsets.ISO_8859_1)
            val sepIdx = text.indexOf("\r\n\r\n")
            val headerPart = if (sepIdx >= 0) text.substring(0, sepIdx) else text
            val body = if (sepIdx >= 0) text.substring(sepIdx + 4) else ""
            val lines = headerPart.split("\r\n")
            if (lines.isEmpty()) return null
            val startLine = lines[0]
            val headers = LinkedHashMap<String, MutableList<String>>()
            val order = ArrayList<String>()
            // Tolerant header continuation: lines starting with space/tab append to
            // the prior header.
            var lastKey: String? = null
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                if (line[0].isWhitespace() && lastKey != null) {
                    val cur = headers[lastKey]?.lastOrNull() ?: continue
                    val merged = cur.trimEnd() + " " + line.trim()
                    headers[lastKey]?.let { it[it.lastIndex] = merged }
                    continue
                }
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val rawName = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                val canonical = COMPACT_ALIASES[rawName.lowercase()] ?: rawName.lowercase()
                if (canonical !in order) order.add(canonical)
                headers.getOrPut(canonical) { ArrayList() }.add(value)
                lastKey = canonical
            }
            // Parse start line.
            val parts = startLine.split(' ', limit = 3)
            return if (parts.size >= 2 && parts[0] == "SIP/2.0") {
                val code = parts[1].toIntOrNull() ?: return null
                val reason = if (parts.size > 2) parts[2] else null
                SipMessage(
                    method = null,
                    statusCode = code,
                    statusText = reason,
                    headers = headers,
                    headerOrder = order,
                    body = body,
                )
            } else if (parts.size >= 3 && parts[2] == "SIP/2.0") {
                SipMessage(
                    method = parts[0],
                    requestUri = parts[1],
                    headers = headers,
                    headerOrder = order,
                    body = body,
                )
            } else null
        }

        /** Best-effort `name=value` extraction from a parameter-bearing header
         *  value (e.g. `<sip:foo>;branch=z9hG4bK.abc;rport=4321`). */
        fun extractParam(raw: String, paramName: String): String? {
            // Split on `;` and `,` carefully — parameters can be quoted.
            var depth = 0
            val chunks = ArrayList<String>()
            val cur = StringBuilder()
            for (ch in raw) {
                if (ch == '<') depth++
                if (ch == '>') depth--
                if (ch == ';' && depth == 0) { chunks += cur.toString(); cur.setLength(0) }
                else cur.append(ch)
            }
            chunks += cur.toString()
            for (chunk in chunks) {
                val trimmed = chunk.trim()
                val eq = trimmed.indexOf('=')
                if (eq < 0) {
                    if (trimmed.equals(paramName, ignoreCase = true)) return ""
                    continue
                }
                val key = trimmed.substring(0, eq).trim()
                if (key.equals(paramName, ignoreCase = true)) {
                    var v = trimmed.substring(eq + 1).trim()
                    if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
                        v = v.substring(1, v.length - 1)
                    }
                    return v
                }
            }
            return null
        }
    }
}

/** Mutable builder used by [SipDialer] to assemble outbound requests/responses.
 *  Doesn't enforce header ordering rules — happy-path generation only. */
class SipMessageBuilder(
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int = 0,
    val statusText: String? = null,
) {
    private val headers = LinkedHashMap<String, MutableList<String>>()
    private val order = ArrayList<String>()
    var body: String = ""

    fun add(name: String, value: String): SipMessageBuilder {
        val key = name.lowercase()
        if (key !in order) order.add(key)
        headers.getOrPut(key) { ArrayList() }.add(value)
        return this
    }

    fun replace(name: String, value: String): SipMessageBuilder {
        val key = name.lowercase()
        if (key !in order) order.add(key)
        headers[key] = mutableListOf(value)
        return this
    }

    fun build(): SipMessage = SipMessage(
        method = method,
        requestUri = requestUri,
        statusCode = statusCode,
        statusText = statusText,
        headers = headers.mapValues { it.value.toList() },
        // Preserve insertion order, but reflect canonical lowercase names back
        // through the header map's first-emit form so callers can re-key
        // case-insensitively if they want to.
        headerOrder = order.map { canonicalCase(it) },
        body = body,
    )

    /** Title-case standard SIP headers ("Call-ID", "From", "Via") for cosmetics. */
    private fun canonicalCase(lc: String): String = when (lc) {
        "call-id" -> "Call-ID"
        "cseq" -> "CSeq"
        "www-authenticate" -> "WWW-Authenticate"
        "user-agent" -> "User-Agent"
        else -> lc.split('-').joinToString("-") { p ->
            p.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
