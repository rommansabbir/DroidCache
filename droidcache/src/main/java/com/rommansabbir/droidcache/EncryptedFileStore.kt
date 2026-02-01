package com.rommansabbir.droidcache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * File-backed encrypted store with crash-safe writes and .bak recovery.
 *
 * Files:
 * - main: <filesDir>/cache/<fileName>
 * - tmp : <fileName>.tmp (staging for atomic write)
 * - bak : <fileName>.bak (last known-good backup)
 *
 * Write algorithm (crash-safe):
 * 1) write tmp
 * 2) fsync tmp
 * 3) rotate: delete bak, rename main->bak (if exists)
 * 4) rename tmp->main
 * 5) if step 4 fails, attempt restore bak->main
 *
 * Read algorithm:
 * 1) try main
 * 2) if fails, try bak and restore it to main
 */
internal class EncryptedFileStore(
    context: Context,
    fileName: String,
    private val cryptoEngine: CryptoEngine,
    private val logger: CacheLogger
) {
    private val dir: File = File(context.filesDir, "cache").apply { mkdirs() }

    private val mainFile: File = File(dir, fileName)
    private val tmpFile: File = File(dir, "$fileName.tmp")
    private val bakFile: File = File(dir, "$fileName.bak")

    suspend fun read(): MutableMap<String, ByteArray> = withContext(Dispatchers.IO) {
        // Try main first, fallback to bak.
        tryRead(mainFile)?.let { return@withContext it }

        logger.d("Main file read failed; attempting .bak recovery")
        val recovered = tryRead(bakFile)
        if (recovered != null) {
            // Restore backup to main for future reads.
            runCatching {
                if (mainFile.exists()) mainFile.delete()
                bakFile.copyTo(mainFile, overwrite = true)
                logger.d("Recovered from .bak and restored main")
            }
            return@withContext recovered
        }

        logger.d("No readable cache file; starting fresh")
        mutableMapOf()
    }

    private fun tryRead(file: File): MutableMap<String, ByteArray>? {
        if (!file.exists()) return null
        return runCatching {
            val raw = file.readBytes()
            if (raw.isEmpty()) return@runCatching mutableMapOf()

            // File format:
            // [ivLen:1 byte][iv:ivLen bytes][ciphertext:rest]
            val ivLen = raw[0].toInt()
            if (ivLen <= 0 || raw.size < 1 + ivLen) return@runCatching null

            val iv = raw.copyOfRange(1, 1 + ivLen)
            val data = raw.copyOfRange(1 + ivLen, raw.size)

            val decrypted = cryptoEngine.decrypt(EncryptedPayload(iv, data))
            BinaryMapCodec.decode(decrypted)
        }.getOrNull()
    }

    suspend fun write(map: Map<String, ByteArray>) = withContext(Dispatchers.IO) {
        val encrypted = cryptoEngine.encrypt(BinaryMapCodec.encode(map))

        // 1) Write TMP
        FileOutputStream(tmpFile).use { fos ->
            fos.write(encrypted.iv.size)      // 1 byte
            fos.write(encrypted.iv)
            fos.write(encrypted.data)
            fos.fd.sync() // 2) fsync tmp
        }

        // 3) Rotate backups
        runCatching { if (bakFile.exists()) bakFile.delete() }
        if (mainFile.exists()) {
            val renamed = mainFile.renameTo(bakFile)
            if (!renamed) {
                // Not fatal, but we lose backup rotation.
                logger.d("Failed to rotate main -> bak")
            }
        }

        // 4) Atomic-ish rename tmp -> main
        val commitOk = tmpFile.renameTo(mainFile)
        if (!commitOk) {
            logger.d("Commit rename tmp->main failed; attempting restore from bak")
            // 5) restore best-effort
            if (bakFile.exists()) {
                runCatching {
                    if (mainFile.exists()) mainFile.delete()
                    bakFile.renameTo(mainFile)
                }
            }
            throw IllegalStateException("Failed to commit cache write")
        }
    }

    /**
     * Deletes all files associated with this store: the main file, the backup file,
     * and any temporary files. This effectively clears all cached data.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mainFile.delete()
        tmpFile.delete()
        bakFile.delete()
    }

    /**
     * @return The [File] object representing the primary cache file.
     * This is intended for testing and debugging purposes only.
     */
    internal fun mainPath(): File = mainFile
    internal fun bakPath(): File = bakFile
}
