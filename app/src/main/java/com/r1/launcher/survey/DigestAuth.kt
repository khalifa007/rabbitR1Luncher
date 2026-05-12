package com.r1.launcher.survey

import java.security.MessageDigest

/**
 * RFC 2617 HTTP Digest Access Authentication, the only flavor SIP cares about
 * for REGISTER / INVITE challenge-response. Twilio, Plivo, Voip.ms, Asterisk —
 * all use MD5 by default. We support MD5 + qop=auth + nc/cnonce; anything
 * exotic (qop=auth-int, SHA-256, mutual auth) is out of scope.
 *
 * Inbound challenge (in a 401 or 407):
 *   WWW-Authenticate: Digest realm="foo", nonce="…", algorithm=MD5, qop="auth", …
 *
 * Outbound response:
 *   Authorization: Digest username="u", realm="foo", nonce="…", uri="sip:…",
 *     response="<MD5>", algorithm=MD5, qop=auth, nc=00000001, cnonce="…"
 */
object DigestAuth {

    data class Challenge(
        val realm: String,
        val nonce: String,
        val algorithm: String = "MD5",
        val qop: String? = null,    // "auth" or null (RFC 2069 fallback)
        val opaque: String? = null,
    )

    /** Parse a `WWW-Authenticate` or `Proxy-Authenticate` header value. Returns
     *  null if scheme is not Digest or required tokens are missing. */
    fun parseChallenge(headerValue: String): Challenge? {
        val trimmed = headerValue.trim()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        val tail = trimmed.substring("Digest".length).trim()
        val params = splitDigestParams(tail)
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val algorithm = params["algorithm"] ?: "MD5"
        val qopRaw = params["qop"]
        // qop can be a comma-separated list ("auth,auth-int"); prefer "auth".
        val qop = qopRaw?.split(',')?.map { it.trim() }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }
        return Challenge(
            realm = realm,
            nonce = nonce,
            algorithm = algorithm,
            qop = qop,
            opaque = params["opaque"],
        )
    }

    /**
     * Compute the digest response and assemble the full Authorization header
     * value (everything after the "Authorization: " prefix).
     */
    fun buildAuthorizationHeader(
        challenge: Challenge,
        method: String,
        uri: String,
        username: String,
        password: String,
        nonceCount: Int = 1,
        cnonceProvider: () -> String = { defaultCnonce() },
    ): String {
        val ha1 = md5Hex("$username:${challenge.realm}:$password")
        val ha2 = md5Hex("$method:$uri")
        val response: String
        val parts = LinkedHashMap<String, String>()
        parts["username"] = quote(username)
        parts["realm"] = quote(challenge.realm)
        parts["nonce"] = quote(challenge.nonce)
        parts["uri"] = quote(uri)

        if (challenge.qop != null) {
            val nc = "%08x".format(nonceCount)
            val cnonce = cnonceProvider()
            response = md5Hex("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2")
            parts["qop"] = challenge.qop
            parts["nc"] = nc
            parts["cnonce"] = quote(cnonce)
        } else {
            // RFC 2069 fallback
            response = md5Hex("$ha1:${challenge.nonce}:$ha2")
        }
        parts["response"] = quote(response)
        parts["algorithm"] = challenge.algorithm
        challenge.opaque?.let { parts["opaque"] = quote(it) }
        return "Digest " + parts.entries.joinToString(", ") { (k, v) -> "$k=$v" }
    }

    private fun splitDigestParams(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            // Skip whitespace and separators
            while (i < n && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= n) break
            // Read key
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            val key = s.substring(keyStart, i).lowercase()
            // Skip = and whitespace
            while (i < n && (s[i].isWhitespace() || s[i] == '=')) i++
            // Read value: quoted or token
            val value: String
            if (i < n && s[i] == '"') {
                i++ // skip opening quote
                val valStart = i
                while (i < n && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < n) i++
                    i++
                }
                value = s.substring(valStart, i)
                if (i < n) i++ // skip closing quote
            } else {
                val valStart = i
                while (i < n && s[i] != ',' && !s[i].isWhitespace()) i++
                value = s.substring(valStart, i)
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun quote(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.ISO_8859_1))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val hi = (b.toInt() ushr 4) and 0xF
            val lo = b.toInt() and 0xF
            sb.append(hex(hi)); sb.append(hex(lo))
        }
        return sb.toString()
    }

    private fun hex(v: Int): Char = if (v < 10) ('0' + v) else ('a' + (v - 10))

    private fun defaultCnonce(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
