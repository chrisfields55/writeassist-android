package com.example.writeassist

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ApiCredentials {
    private const val ALIAS = "writeassist_api_key_v1"
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun save(context: Context, value: String) {
        if (value.isBlank()) {
            context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("key", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply()
    }
    fun load(context: Context): String? {
        return try {
        val p = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
        val iv = Base64.decode(p.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val ciphertext = Base64.decode(p.getString("key", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) { null }
    }
}
