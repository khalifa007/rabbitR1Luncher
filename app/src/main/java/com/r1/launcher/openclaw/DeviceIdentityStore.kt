package com.r1.launcher.openclaw

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val publicKeyRawBase64: String,
    val privateKeyPkcs8Base64: String,
    val createdAtMs: Long,
)

/**
 * Ed25519 keypair persistence + signing, ported from openclaw-src.
 *
 * Why: the gateway's `connect` RPC requires a v3 device-auth payload signed with
 * the device's Ed25519 private key. Without it, the gateway rejects the handshake.
 * Uses Bouncy Castle's lightweight API directly (avoids JCA provider issues that
 * trip R8 minification).
 */
class DeviceIdentityStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val identityFile = File(context.applicationContext.filesDir, "openclaw/identity/device.json")
    @Volatile private var cached: DeviceIdentity? = null

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        cached?.let { return it }
        val existing = read()
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = generate()
        write(fresh)
        cached = fresh
        return fresh
    }

    fun signPayload(payload: String, identity: DeviceIdentity): String? = try {
        val privateKeyBytes = Base64.decode(identity.privateKeyPkcs8Base64, Base64.DEFAULT)
        val pkInfo = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(privateKeyBytes)
        val rawPrivate = org.bouncycastle.asn1.DEROctetString.getInstance(pkInfo.parsePrivateKey()).octets
        val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(rawPrivate, 0)
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer().apply { init(true, privateKey) }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(bytes, 0, bytes.size)
        base64Url(signer.generateSignature())
    } catch (_: Throwable) {
        null
    }

    fun publicKeyBase64Url(identity: DeviceIdentity): String? = try {
        base64Url(Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT))
    } catch (_: Throwable) {
        null
    }

    private fun read(): DeviceIdentity? = try {
        if (!identityFile.exists()) null
        else {
            val raw = identityFile.readText(Charsets.UTF_8)
            json.decodeFromString(DeviceIdentity.serializer(), raw)
                .takeIf { it.deviceId.isNotBlank() && it.publicKeyRawBase64.isNotBlank() && it.privateKeyPkcs8Base64.isNotBlank() }
        }
    } catch (_: Throwable) {
        null
    }

    private fun write(identity: DeviceIdentity) {
        runCatching {
            identityFile.parentFile?.mkdirs()
            identityFile.writeText(
                json.encodeToString(DeviceIdentity.serializer(), identity),
                Charsets.UTF_8,
            )
        }
    }

    private fun generate(): DeviceIdentity {
        val gen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
        gen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val pub = kp.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
        val priv = kp.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
        val rawPub = pub.encoded
        val pkcs8 = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(priv).encoded
        return DeviceIdentity(
            deviceId = sha256Hex(rawPub),
            publicKeyRawBase64 = Base64.encodeToString(rawPub, Base64.NO_WRAP),
            privateKeyPkcs8Base64 = Base64.encodeToString(pkcs8, Base64.NO_WRAP),
            createdAtMs = System.currentTimeMillis(),
        )
    }

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        fun buildAuthPayloadV3(
            deviceId: String,
            clientId: String,
            clientMode: String,
            role: String,
            scopes: List<String>,
            signedAtMs: Long,
            token: String?,
            nonce: String,
            platform: String?,
            deviceFamily: String?,
        ): String = listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMs.toString(),
            token.orEmpty(),
            nonce,
            normalize(platform),
            normalize(deviceFamily),
        ).joinToString("|")

        private fun normalize(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return ""
            val sb = StringBuilder(trimmed.length)
            for (ch in trimmed) sb.append(if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch)
            return sb.toString()
        }
    }
}
