package com.kanayama.wifiscreen

import org.bouncycastle.crypto.engines.ChaChaEngine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Open (atv=0) Lelink v2 pairing. Fresh identity/session keys; no vendor keys or native SDK. */
class LelinkPairing(private val random: SecureRandom = SecureRandom()) {
    private enum class Stage { NEW, SETUP, CHALLENGED, READY, FAILED }
    private var stage = Stage.NEW
    private val identity = Ed25519PrivateKeyParameters(random)
    private var clientIdentity = ByteArray(0)
    private var clientRandom = ByteArray(0)
    private val receiverRandom = ByteArray(32).also { random.nextBytes(it) }
    private var clientCurve = ByteArray(0)
    private var receiverCurve = ByteArray(0)
    private var shared = ByteArray(0)
    private var incoming: LelinkRecords? = null
    private var outgoing: LelinkRecords? = null
    val ready: Boolean get() = stage == Stage.READY

    fun handshake(path: String, body: ByteArray): ByteArray = try {
        val fields = LelinkTlv.decode(body)
        fun field(id: Int, size: Int): ByteArray = fields[id]?.takeIf { it.size == size }
            ?: throw IOException("Invalid pairing field $id")
        if (field(1, 1)[0].toInt() != 1) throw IOException("Unsupported pairing mode")
        val step = field(2, 1)[0].toInt()
        when {
            path == "/lelink-setup" && stage == Stage.NEW && step == 1 -> {
                val header = field(0, 4)
                if (!header.contentEquals(byteArrayOf(0, 1, 0, 1))) throw IOException("Unsupported pairing options")
                val data = field(3, 64)
                clientIdentity = data.copyOfRange(0, 32); clientRandom = data.copyOfRange(32, 64)
                stage = Stage.SETUP
                LelinkTlv.encode(mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(2),
                    3 to (identity.generatePublicKey().encoded + receiverRandom)))
            }
            path == "/lelink-verify" && stage == Stage.SETUP && step == 3 -> {
                val data = field(4, 64)
                if (!MessageDigest.isEqual(clientIdentity, data.copyOfRange(32, 64)))
                    throw IOException("Pairing identity changed")
                clientCurve = data.copyOfRange(0, 32)
                val curve = X25519PrivateKeyParameters(random)
                receiverCurve = curve.generatePublicKey().encoded
                shared = ByteArray(32)
                curve.generateSecret(X25519PublicKeyParameters(clientCurve, 0), shared, 0)
                val signed = Ed25519Signer().apply {
                    init(true, identity)
                    val transcript = receiverCurve + clientCurve
                    update(transcript, 0, transcript.size)
                }.generateSignature()
                val encrypted = signatureCipher(signed, "LELINK-VERIFY_SIGNATURE-KEY", "LELINK-VERIFY-SIGNATURE-NONCE", true)
                stage = Stage.CHALLENGED
                LelinkTlv.encode(mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(4), 4 to (receiverCurve + encrypted)))
            }
            path == "/lelink-verify" && stage == Stage.CHALLENGED && step == 5 -> {
                val signature = signatureCipher(field(5, 64), "LELINK-VERIFY_IDENTITY-KEY", "LEINK-VERIFY-IDENTITY-NONCE", false)
                val verified = Ed25519Signer().apply {
                    init(false, Ed25519PublicKeyParameters(clientIdentity, 0))
                    val transcript = clientCurve + receiverCurve
                    update(transcript, 0, transcript.size)
                }.verifySignature(signature)
                if (!verified) throw IOException("Pairing signature rejected")
                val salt = hash(shared, hash(shared, hash(clientRandom, receiverRandom)))
                val key = hash(salt, "LELINK-IDENTITY-KEY".toByteArray())
                val nonce = hash(salt, "LELINK-IDENTITY-NONCE".toByteArray()).copyOf(8)
                incoming = LelinkRecords(key, nonce); outgoing = LelinkRecords(key, nonce)
                shared.fill(0); shared = ByteArray(0)
                stage = Stage.READY
                LelinkTlv.encode(mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(6), 5 to ByteArray(0)))
            }
            else -> throw IOException("Unexpected pairing step")
        }
    } catch (failure: Exception) {
        stage = Stage.FAILED; shared.fill(0)
        throw IOException("Lelink pairing failed: ${failure.message}", failure)
    }

    private fun signatureCipher(bytes: ByteArray, keyLabel: String, nonceLabel: String, encrypt: Boolean): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(hash(keyLabel.toByteArray(), shared, 16), "AES"),
                IvParameterSpec(hash(nonceLabel.toByteArray(), shared, 16)))
        }.doFinal(bytes)

    fun encrypt(bytes: ByteArray): ByteArray {
        if (!ready) throw IOException("Pairing not completed")
        return outgoing!!.encrypt(bytes)
    }
    fun decrypt(bytes: ByteArray): ByteArray {
        if (!ready) throw IOException("Pairing not completed")
        return try { incoming!!.decrypt(bytes) } catch (error: Exception) {
            stage = Stage.FAILED
            throw error
        }
    }
    private fun hash(a: ByteArray, b: ByteArray, size: Int = 32): ByteArray =
        MessageDigest.getInstance("SHA-512").apply { update(a); update(b) }.digest().copyOf(size)
}

/** The wire format uses 32-bit little-endian TLV, not HomeKit's 8-bit TLV. */
object LelinkTlv {
    fun decode(bytes: ByteArray): Map<Int, ByteArray> {
        if (bytes.size > 4096) throw IOException("Pairing body too large")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val fields = linkedMapOf<Int, ByteArray>()
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 8) throw IOException("Truncated pairing field")
            val id = buffer.int
            val size = buffer.int
            if (size < 0 || size > buffer.remaining() || fields.containsKey(id) || fields.size >= 16)
                throw IOException("Invalid pairing TLV")
            fields[id] = ByteArray(size).also { buffer.get(it) }
        }
        return fields
    }
    fun encode(fields: Map<Int, ByteArray>): ByteArray =
        ByteBuffer.allocate(fields.values.sumOf { 8 + it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
            fields.forEach { (id, value) -> putInt(id); putInt(value.size); put(value) }
        }.array()
}

/** Legacy v2 records authenticate plaintext, advancing whole ChaCha blocks per record. */
class LelinkRecords(key: ByteArray, nonce: ByteArray) {
    private val cipher = ChaChaEngine(20).apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
    private var failed = false
    companion object {
        const val MAX_PLAINTEXT = 5120
        fun size(prefix: ByteArray): Int {
            if (prefix.size != 4) throw IOException("Invalid encrypted record header")
            val length = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).int
            if (length !in 1..MAX_PLAINTEXT) throw IOException("Encrypted record exceeds limit")
            return length
        }
    }
    private fun stream(size: Int) = ByteArray(size).also { cipher.processBytes(it, 0, size, it, 0) }
    private fun tag(plain: ByteArray, key: ByteArray): ByteArray = ByteArray(16).also {
        Poly1305().apply { init(KeyParameter(key.copyOf(32))); update(plain, 0, plain.size); doFinal(it, 0) }
    }
    fun encrypt(plain: ByteArray): ByteArray {
        if (failed || plain.size !in 1..MAX_PLAINTEXT) throw IOException("Invalid encrypted record")
        val authKey = stream(64)
        val stream = stream((plain.size + 63) / 64 * 64)
        val encrypted = ByteArray(plain.size) { (plain[it].toInt() xor stream[it].toInt()).toByte() }
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(plain.size).array() + encrypted + tag(plain, authKey)
    }
    fun decrypt(record: ByteArray): ByteArray {
        if (failed || record.size < 20) throw IOException("Invalid encrypted record")
        val size = size(record.copyOf(4))
        if (record.size != size + 20) throw IOException("Truncated encrypted record")
        val authKey = stream(64)
        val stream = stream((size + 63) / 64 * 64)
        val plain = ByteArray(size) { (record[it + 4].toInt() xor stream[it].toInt()).toByte() }
        if (!MessageDigest.isEqual(tag(plain, authKey), record.copyOfRange(size + 4, record.size))) {
            failed = true
            throw IOException("Encrypted record authentication failed")
        }
        return plain
    }
}
