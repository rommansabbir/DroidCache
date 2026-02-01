package com.rommansabbir.droidcache

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeyStoreCryptoEngine : CryptoEngine {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_ALIAS = "SIMPLE_CACHE_AES"
    }

    /**
     * Retrieves an existing AES encryption key from the AndroidKeyStore or creates a new one
     * if it doesn't exist.
     *
     * This function first attempts to load a secret key with the alias [KEY_ALIAS] from the
     * AndroidKeyStore. If the key is found, it is returned.
     *
     * If the key is not found, a new [SecretKey] is generated using the AES algorithm and
     * stored in the AndroidKeyStore under the same alias. The key is configured for both
     * encryption and decryption using GCM block mode and no padding, with a key size of 256 bits.
     *
     * @return The existing or newly created [SecretKey].
     */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * Encrypts the given byte array using AES/GCM/NoPadding.
     *
     * It initializes a [Cipher] for encryption, using a secret key retrieved or created
     * by [getOrCreateKey]. The initialization vector (IV) is generated automatically
     * by the cipher. The encrypted data and the IV are then encapsulated in an
     * [EncryptedPayload] object.
     *
     * @param plain The raw [ByteArray] to be encrypted.
     * @return An [EncryptedPayload] containing the initialization vector (IV) and the encrypted data.
     */
    override fun encrypt(plain: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedPayload(cipher.iv, cipher.doFinal(plain))
    }

    /**
     * Decrypts the given [EncryptedPayload] using the AES key from the AndroidKeyStore.
     *
     * This function initializes a [Cipher] for decryption in AES/GCM mode. It retrieves
     * the secret key using [getOrCreateKey] and uses the Initialization Vector (IV) and
     * authentication tag length from the provided payload. It then decrypts the encrypted
     * data and returns the original plaintext.
     *
     * @param payload The [EncryptedPayload] object containing the encrypted data and the IV.
     * @return A [ByteArray] containing the decrypted plaintext data.
     * @throws java.security.GeneralSecurityException if decryption fails due to reasons like
     *         incorrect key, corrupted data, or invalid tag.
     */
    override fun decrypt(payload: EncryptedPayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, payload.iv)
        )
        return cipher.doFinal(payload.data)
    }
}
