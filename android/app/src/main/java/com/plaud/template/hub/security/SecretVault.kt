package com.plaud.template.hub.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores provider keys and device credentials encrypted by a non-exportable Keystore key. */
class SecretVault(context: Context) {
    private val prefs = context.getSharedPreferences("hub_secret_vault", Context.MODE_PRIVATE)

    fun put(name: String, secret: String) {
        require(name.matches(Regex("[a-z0-9_.-]+")))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + cipher.iv.size + encrypted.size)
        packed[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(packed, 1)
        encrypted.copyInto(packed, 1 + cipher.iv.size)
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? = runCatching {
        val packed = Base64.decode(prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val ivLength = packed.first().toInt() and 0xff
        require(ivLength in 12..16 && packed.size > ivLength + 1)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, packed.copyOfRange(1, 1 + ivLength))
        )
        String(cipher.doFinal(packed.copyOfRange(1 + ivLength, packed.size)), Charsets.UTF_8)
    }.getOrNull()

    fun isConfigured(name: String) = !get(name).isNullOrBlank()
    fun remove(name: String) = prefs.edit().remove(name).apply()

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        const val SILICONFLOW_API_KEY = "provider.siliconflow.key"
        const val DEEPSEEK_API_KEY = "provider.deepseek.key"
        const val IDEASHELL_BEARER_TOKEN = "mcp.ideashell.token"
        const val DINGTALK_DEVICE_SECRET = "device.dingtalk.secret"
        private const val KEY_ALIAS = "recorder_hub_secret_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
