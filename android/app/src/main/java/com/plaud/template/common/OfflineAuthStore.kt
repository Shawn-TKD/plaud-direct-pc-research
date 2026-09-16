package com.plaud.template.common

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the one-time cloud bootstrap material needed by the BLE handshake.
 *
 * The device signature, BLE bind identity, and Plaud RSA pair are encrypted with a
 * non-exportable Android Keystore key. The SDK also keeps the pair in its process-local holder;
 * this store makes later offline connections and an owner-controlled PC export possible.
 */
object OfflineAuthStore {
    private const val PREFS = "plaud_offline_auth"
    private const val KEY_ALIAS = "plaud_offline_auth_v1"
    private const val KEY_BLOB = "encrypted_blob"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    data class Material(
        val sn: String,
        val signature: String,
        val bindToken: String,
        val rsaPublicKey: String? = null,
        val rsaPrivateKey: String? = null
    )

    fun save(context: Context, material: Material): Boolean = runCatching {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "Android Keystore AES/GCM requires Android 6.0+"
        }
        val plaintext = JSONObject()
            .put("sn", material.sn)
            .put("signature", material.signature)
            .put("bindToken", material.bindToken)
            .put("rsaPublicKey", material.rsaPublicKey)
            .put("rsaPrivateKey", material.rsaPrivateKey)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val packed = ByteArray(1 + cipher.iv.size + ciphertext.size)
        packed[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(packed, 1)
        ciphertext.copyInto(packed, 1 + cipher.iv.size)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BLOB, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
        true
    }.getOrElse {
        AppLog.e("OfflineAuthStore", "Unable to save encrypted handshake material", it)
        false
    }

    fun load(context: Context, expectedSn: String): Material? =
        loadAny(context)?.takeIf { it.sn == expectedSn }

    /**
     * Decrypts the locally provisioned material without requiring the caller to already know the
     * device serial number. This is only used by the one-shot, public-key-encrypted PC exporter.
     */
    fun loadAny(context: Context): Material? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOB, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = packed.first().toInt() and 0xff
        require(ivLength in 12..16 && packed.size > 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, packed.copyOfRange(1, 1 + ivLength))
        )
        val obj = JSONObject(
            String(cipher.doFinal(packed.copyOfRange(1 + ivLength, packed.size)), Charsets.UTF_8)
        )
        Material(
            sn = obj.getString("sn"),
            signature = obj.getString("signature"),
            bindToken = obj.getString("bindToken"),
            rsaPublicKey = obj.optString("rsaPublicKey").takeIf { it.isNotBlank() && it != "null" },
            rsaPrivateKey = obj.optString("rsaPrivateKey").takeIf { it.isNotBlank() && it != "null" }
        ).takeIf { it.signature.isNotBlank() && it.bindToken.isNotBlank() }
    }.getOrElse {
        AppLog.e("OfflineAuthStore", "Unable to load encrypted handshake material", it)
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
