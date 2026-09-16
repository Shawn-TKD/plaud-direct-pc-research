package com.plaud.template.common

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Produces a one-shot hybrid-encrypted bootstrap package for the owner's PC.
 *
 * The request is deliberately file-triggered inside app-private storage, so no exported Android
 * component can ask for credentials. The plaintext exists only in this process and is encrypted
 * to a PC-generated RSA public key before any output file is created.
 */
object PcBootstrapExporter {
    private const val REQUEST_FILE = "pc-bootstrap-public.pem"
    private const val OUTPUT_FILE = "pc-bootstrap-v1.json"
    private const val AAD_TEXT = "plaud-pc-bootstrap-v1"

    fun exportIfRequested(context: Context): Boolean {
        val request = File(context.filesDir, REQUEST_FILE)
        if (!request.isFile) return false

        return runCatching {
            val material = OfflineAuthStore.loadAny(context)
                ?: error("No encrypted offline authentication material")
            require(!material.rsaPublicKey.isNullOrBlank()) { "Cached Plaud RSA public key missing" }
            require(!material.rsaPrivateKey.isNullOrBlank()) { "Cached Plaud RSA private key missing" }

            val pcPublicKey = parseRsaPublicKey(request.readText(Charsets.US_ASCII))
            val plaintext = JSONObject()
                .put("version", 1)
                .put("sn", material.sn)
                .put("signature", material.signature)
                .put("bindToken", material.bindToken)
                .put("rsaPublicKey", material.rsaPublicKey)
                .put("rsaPrivateKey", material.rsaPrivateKey)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val contentCipher = Cipher.getInstance("AES/GCM/NoPadding")
            contentCipher.init(Cipher.ENCRYPT_MODE, aesKey)
            contentCipher.updateAAD(AAD_TEXT.toByteArray(Charsets.US_ASCII))
            val ciphertext = contentCipher.doFinal(plaintext)

            val wrapCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            wrapCipher.init(
                Cipher.ENCRYPT_MODE,
                pcPublicKey,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT
                )
            )
            val wrappedKey = wrapCipher.doFinal(aesKey.encoded)

            val output = JSONObject()
                .put("version", 1)
                .put("keyWrap", "RSA-OAEP-SHA256-MGF1-SHA256")
                .put("contentCipher", "AES-256-GCM")
                .put("wrappedKey", b64(wrappedKey))
                .put("nonce", b64(contentCipher.iv))
                .put("ciphertext", b64(ciphertext))
                .toString()

            val destination = File(context.filesDir, OUTPUT_FILE)
            val temporary = File(context.filesDir, "$OUTPUT_FILE.tmp")
            temporary.writeText(output, Charsets.UTF_8)
            require(temporary.renameTo(destination)) { "Unable to finalize bootstrap package" }
            request.delete()
            plaintext.fill(0)
            AppLog.i("PcBootstrapExporter", "Encrypted PC bootstrap package created (${destination.length()} bytes)")
            true
        }.getOrElse {
            AppLog.e("PcBootstrapExporter", "Unable to create encrypted PC bootstrap package", it)
            false
        }
    }

    private fun parseRsaPublicKey(pem: String) = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(
            Base64.decode(
                pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace(Regex("\\s+"), ""),
                Base64.DEFAULT
            )
        )
    )

    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
}
