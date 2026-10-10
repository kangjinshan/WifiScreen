package com.kanayama.wifiscreen

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** V2's native frame encryptor updates its CBC IV after each encrypted keyframe segment. */
class LelinkVideoCipher {
    private var time: String? = null
    private var iv = ByteArray(0)
    private val key = SecretKeySpec(derive(LegacyAvc.V2_CIPHER), "AES")

    // Decoder-thread only. Call once per encrypted input, before caching the clear access unit.
    // A decoder rebuild or repeated SPS/PPS must NOT reset this independent stream state.
    fun clearPicture(wire: ByteArray, streamTime: String): ByteArray {
        if (streamTime.isEmpty() || wire.size < 5 || wire.size > LegacyAvc.MAX_PAYLOAD ||
            ByteBuffer.wrap(wire).int != wire.size - 4) throw IOException("Invalid v2 encrypted picture")
        if (time != streamTime) { time = streamTime; iv = derive(streamTime) }
        val clear = wire.copyOf()
        val count = (wire.size - 5) / 32 * 16
        if (count > 0) {
            val nextIv = wire.copyOfRange(5 + count - 16, 5 + count)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            cipher.doFinal(wire, 5, count).copyInto(clear, 5)
            iv = nextIv
        }
        return clear
    }

    private fun derive(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .map { (it.toInt() xor 0x78).toByte() }.toByteArray()
}
