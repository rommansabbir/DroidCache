package com.rommansabbir.droidcache

/**
 * Abstraction for encrypting/decrypting byte arrays.
 *
 * Production uses AndroidKeyStore-backed AES-GCM.
 * Tests use an in-memory software AES implementation.
 */
interface CryptoEngine {
    fun encrypt(plain: ByteArray): EncryptedPayload
    fun decrypt(payload: EncryptedPayload): ByteArray
}