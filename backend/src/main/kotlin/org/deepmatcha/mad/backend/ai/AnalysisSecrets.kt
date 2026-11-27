package org.deepmatcha.mad.backend.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AnalysisSecrets {
    fun seal(value: String): String
    fun unseal(encrypted: String): String
}

/** The store holds only authenticated ciphertext. The key stays in Android Keystore. */
internal object AndroidAnalysisSecrets : AnalysisSecrets {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "org.deepmatcha.mad.analysis.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    override fun seal(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        check(cipher.iv.size == IV_BYTES)
        val plaintext = value.toByteArray(Charsets.UTF_8)
        return try {
            val ciphertext = cipher.doFinal(plaintext)
            Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun unseal(encrypted: String): String {
        val bytes = Base64.getDecoder().decode(encrypted)
        check(bytes.size >= IV_BYTES + TAG_BITS / 8)
        val key = checkNotNull(keystore().getKey(ALIAS, null) as? SecretKey)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        val plaintext = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        return try {
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        val existing = keystore().getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keystore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}
