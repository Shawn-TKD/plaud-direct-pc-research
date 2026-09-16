package com.plaud.template.common

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Imports the password-encrypted portable identity produced by plaud-pc-bridge. */
object PlaudPortableImporter {
    private const val PURPOSE = "plaud-device-auth-portable"
    private const val AAD = "plaud-device-auth-portable-v1"

    data class Result(val serialNumber: String, val suffix: String)

    fun importPackage(context: Context, envelopeText: String, password: CharArray): Result {
        require(password.size >= 20) { "传输密码至少需要 20 个字符" }
        val envelope = JSONObject(envelopeText)
        require(envelope.optInt("version") == 1 && envelope.optString("purpose") == PURPOSE) {
            "不是兼容的 PLAUD 离线凭据包"
        }
        require(envelope.optString("contentEncryption") == "AES-256-GCM" &&
            envelope.optString("kdf") == "scrypt") { "不支持的凭据包加密格式" }
        val params = envelope.getJSONObject("scrypt")
        require(params.optInt("n") == 32768 && params.optInt("r") == 8 && params.optInt("p") == 1) {
            "不支持的 scrypt 参数"
        }
        val salt = Base64.decode(params.getString("salt"), Base64.DEFAULT)
        val nonce = Base64.decode(envelope.getString("nonce"), Base64.DEFAULT)
        val ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.DEFAULT)
        require(salt.size == 16 && nonce.size == 12) { "凭据包参数长度异常" }

        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        val key = SCrypt.generate(passwordBytes, salt, 32768, 8, 1, 32)
        passwordBytes.fill(0)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(AAD.toByteArray(Charsets.US_ASCII))
                doFinal(ciphertext)
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("密码错误，或凭据包已经损坏", error)
        } finally {
            key.fill(0)
            password.fill('\u0000')
        }

        try {
            val material = JSONObject(String(plaintext, Charsets.UTF_8))
            require(material.optInt("version") == 1) { "凭据内容版本不兼容" }
            val sn = material.getString("sn")
            val signature = material.getString("signature")
            val bindToken = material.getString("bindToken")
            val publicKey = material.getString("rsaPublicKey")
            val privateKey = material.getString("rsaPrivateKey")
            require(sn.isNotBlank() && signature.isNotBlank() && bindToken.isNotBlank() &&
                publicKey.contains("BEGIN PUBLIC KEY") && privateKey.contains("BEGIN PRIVATE KEY")) {
                "凭据包缺少完整的设备握手材料"
            }
            check(OfflineAuthStore.save(context, OfflineAuthStore.Material(
                sn = sn,
                signature = signature,
                bindToken = bindToken,
                rsaPublicKey = publicKey,
                rsaPrivateKey = privateKey
            ))) { "无法写入 Android Keystore" }
            return Result(sn, sn.takeLast(4))
        } finally {
            plaintext.fill(0)
        }
    }
}
