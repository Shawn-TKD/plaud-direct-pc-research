package com.plaud.template.hub.device.feishu

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class D3200FileSecret(val fileId: Long, val fileSize: Long)

/** Per-connection ECDH session and per-file AES-CTR keys used by D3200. */
internal class D3200Crypto {
    private var keyPair: KeyPair? = null
    private var sessionKey: ByteArray? = null
    private val files = mutableMapOf<Long, Pair<ByteArray, ByteArray>>()

    val hasSession: Boolean get() = sessionKey != null

    fun publicKey(): ByteArray {
        val pair = keyPair ?: KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().also { keyPair = it }
        val point = (pair.public as ECPublicKey).w
        return byteArrayOf(0x04) + fixed(point.affineX, 32) + fixed(point.affineY, 32)
    }

    fun completeHandshake(payload: ByteArray): Boolean {
        if (payload.size < 97) return false
        val pair = keyPair ?: run { publicKey(); keyPair!! }
        val params = (pair.public as ECPublicKey).params
        val point = ECPoint(
            BigInteger(1, payload.copyOfRange(1, 33)),
            BigInteger(1, payload.copyOfRange(33, 65))
        )
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(peer, true)
        val shared = normalize(agreement.generateSecret(), 32)
        if (!constantTimeEquals(shared, payload.copyOfRange(65, 97))) return false
        sessionKey = hkdfSha256(shared, byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3), 32)
        return true
    }

    fun prepareFile(payload: ByteArray): D3200FileSecret {
        val session = sessionKey ?: error("尚未建立录音解密会话")
        require(payload.size >= 87) { "录音文件密钥响应过短" }
        val view = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val fileId = view.int.toLong() and 0xffffffffL
        val fileSize = view.int.toLong() and 0xffffffffL
        val nonce = payload.copyOfRange(8, 24)
        val encryptedKey = payload.copyOfRange(24, 70)
        val sessionNonce = payload.copyOfRange(70, 86)
        val errorCode = payload[86].toInt() and 0xff
        check(errorCode == 0) { "设备拒绝导出（错误码 $errorCode）" }
        val plain = aesCtr(encryptedKey, session, sessionNonce)
        val magic = "soundcored3200".toByteArray(Charsets.US_ASCII)
        require(plain.size >= 46 && constantTimeEquals(plain.copyOfRange(0, 14), magic)) {
            "录音文件密钥校验失败"
        }
        files[fileId] = plain.copyOfRange(14, 46) to nonce.copyOfRange(0, 12)
        return D3200FileSecret(fileId, fileSize)
    }

    fun decryptChunk(fileId: Long, sequence: Long, data: ByteArray): ByteArray {
        val (key, nonce) = files[fileId] ?: error("尚未取得录音文件密钥")
        val counter = ByteArray(16)
        nonce.copyInto(counter)
        ByteBuffer.wrap(counter, 12, 4).order(ByteOrder.BIG_ENDIAN).putInt((sequence * 10).toInt())
        return aesCtr(data, key, counter)
    }

    fun clearFile(fileId: Long) { files.remove(fileId) }

    fun reset() {
        keyPair = null
        sessionKey = null
        files.clear()
    }

    private fun aesCtr(data: ByteArray, key: ByteArray, counter: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            doFinal(data)
        }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val output = ByteArrayOutput(length)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    private fun fixed(value: BigInteger, size: Int): ByteArray = normalize(value.toByteArray(), size)

    private fun normalize(value: ByteArray, size: Int): ByteArray = when {
        value.size == size -> value
        value.size > size -> value.copyOfRange(value.size - size, value.size)
        else -> ByteArray(size - value.size) + value
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        a.indices.forEach { diff = diff or (a[it].toInt() xor b[it].toInt()) }
        return diff == 0
    }

    private class ByteArrayOutput(private val limit: Int) {
        private val chunks = ArrayList<ByteArray>()
        var size = 0
            private set
        fun write(bytes: ByteArray) {
            if (size >= limit) return
            chunks += bytes
            size += bytes.size
        }
        fun toByteArray(): ByteArray {
            val out = ByteArray(size)
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(out, offset).also { offset += chunk.size } }
            return out
        }
    }
}
