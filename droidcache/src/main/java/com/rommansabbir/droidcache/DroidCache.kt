package com.rommansabbir.droidcache

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.iterator
import kotlin.math.max
import androidx.core.content.edit

/**
 * SimpleCache
 *
 * Encrypted, coroutine-backed, file-based key-value cache.
 *
 * ✅ No main-thread disk/crypto
 * ✅ In-memory hot reads
 * ✅ AES-GCM encryption via AndroidKeyStore
 * ✅ Crash-safe write with .bak recovery
 * ✅ Write-back (debounced) or Write-through (immediate)
 * ✅ SharedPreferences migration (optional)
 * ✅ Memory pressure handling (optional)
 *
 * ---
 * Usage:
 * ```
 * // Application.onCreate
 * lifecycleScope.launch {
 *   DroidCache.init(
 *     appContext = applicationContext,
 *     config = DroidCache.Config(
 *       persistenceMode = DroidCache.PersistenceMode.WRITE_BACK,
 *       debounceMs = 500,
 *       logger = AndroidCacheLogger(BuildConfig.DEBUG, "DroidCache")
 *     )
 *   )
 * }
 *
 * // Hot path read (memory-only, non-suspending)
 * val uid = DroidCache.getStringFast("USER_ID")
 *
 * // Safe suspend read/write
 * val token = DroidCache.getString("TOKEN")
 * DroidCache.putString("TOKEN", "abc")
 * ```
 */
object DroidCache {

    enum class PersistenceMode {
        /**
         * Write-back: changes are kept in memory and flushed to disk after [Config.debounceMs].
         * Best performance for frequent writes (cache/prefs style).
         */
        WRITE_BACK,

        /**
         * Write-through: every write is committed to disk immediately (no debounce).
         * Stronger durability, slower for frequent writes.
         */
        WRITE_THROUGH
    }

    data class MemoryPolicy(
        /**
         * If true, clears in-memory map when system signals critical memory pressure.
         * Disk remains intact; cache will lazy-reload on next suspend call.
         */
        val clearMemoryOnCriticalTrim: Boolean = true,

        /**
         * Trim threshold at or above which we consider it "critical".
         * Common choice: TRIM_MEMORY_RUNNING_CRITICAL or TRIM_MEMORY_COMPLETE.
         */
        val criticalTrimLevel: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,

        /**
         * If true, we flush pending writes before clearing memory.
         */
        val flushBeforeClear: Boolean = true
    )

    /**
     * Configuration for the optional, one-time migration from `SharedPreferences`.
     * If enabled, on first initialization, the cache will attempt to read all key-value
     * pairs from the specified `SharedPreferences` file, write them to the new encrypted
     * cache, and then clear the old `SharedPreferences` file.
     *
     * @property enabled If true, migration will be attempted on first launch. Defaults to `true`.
     * @property sharedPreferencesName The name of the `SharedPreferences` file to migrate from.
     * Defaults to "SYSTEM_CACHE".
     */
    data class MigrationConfig(
        val enabled: Boolean = true,
        val sharedPreferencesName: String = "SYSTEM_CACHE"
    )

    /**
     * Configuration for initializing the [DroidCache].
     *
     * @property fileName The name of the file on disk where the cache will be stored.
     * @property persistenceMode The strategy for writing changes to disk. See [PersistenceMode].
     * @property debounceMs When using [PersistenceMode.WRITE_BACK], this is the delay in milliseconds
     *                      after the last write before changes are flushed to disk.
     * @property logger A custom logger implementation for internal cache events. Defaults to a no-op logger.
     * @property migration Configuration for one-time migration from Android's SharedPreferences.
     * @property memoryPolicy Configuration for how the cache responds to system memory pressure.
     * @property ioDispatcher The CoroutineDispatcher used for all disk I/O and cryptographic operations.
     *                        Allows tests to inject a specific dispatcher. Production uses [Dispatchers.IO] by default.
     * @property cryptoEngine The engine used for encryption and decryption. Defaults to [AndroidKeyStoreCryptoEngine].
     *                        Allows for custom or test implementations.
     */
    data class Config(
        val fileName: String = "simple_cache.bin",
        val persistenceMode: PersistenceMode = PersistenceMode.WRITE_BACK,
        val debounceMs: Long = 400L,
        val logger: CacheLogger = NoopCacheLogger,
        val migration: MigrationConfig = MigrationConfig(),
        val memoryPolicy: MemoryPolicy = MemoryPolicy(),
        /**
         * Allows tests to inject a scope/dispatcher. Production uses IO scope by default.
         */
        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        val cryptoEngine: CryptoEngine? = null
    )

    private val mutex = Mutex()
    private var initialized = false

    private lateinit var store: EncryptedFileStore
    private lateinit var config: Config

    // Concurrent map allows lock-free fast reads without ConcurrentModification risk.
    private val cache = ConcurrentHashMap<String, ByteArray>()

    // Write-back flush job.
    private var flushJob: Job? = null

    // If memory was cleared under pressure, we'll reload lazily.
    @Volatile private var memoryCleared = false

    private var callbacks: ComponentCallbacks2? = null

    private lateinit var scope: CoroutineScope

    /**
     * Initializes the cache. Must be called once before any other operations.
     * This function is safe to call multiple times; subsequent calls will be ignored.
     *
     * It performs the following actions:
     * 1. Sets up the internal coroutine scope for background operations.
     * 2. Initializes the crypto engine and the encrypted file store.
     * 3. Reads all existing key-value pairs from the encrypted file into the in-memory cache.
     * 4. Optionally migrates data from a specified SharedPreferences file (one-time operation).
     * 5. Optionally registers system memory callbacks to handle low-memory situations.
     *
     * @param appContext The application context, used for file I/O and registering system callbacks.
     * @param config The configuration for the cache, including persistence mode, file name, etc.
     */
    suspend fun init(appContext: Context, config: Config = Config()) {
        if (initialized) return
        this.config = config
        this.scope = CoroutineScope(SupervisorJob() + config.ioDispatcher)
        val crypto = config.cryptoEngine ?: AndroidKeyStoreCryptoEngine()

        store = EncryptedFileStore(
            appContext.applicationContext,
            config.fileName,
            cryptoEngine = crypto,
            config.logger
        )

        // Load persisted state.
        val loaded = store.read()
        cache.putAll(loaded)

        // Optional SP migration (one-time best-effort).
        if (config.migration.enabled) {
            migrateFromSharedPreferences(appContext.applicationContext, config.migration.sharedPreferencesName)
        }

        // Optional memory pressure handling.
        registerMemoryCallbacks(appContext.applicationContext)

        initialized = true
        config.logger.d("Initialized (entries=${cache.size})")
    }

    /**
     * Retrieves the raw byte array for a given [key] from the in-memory cache.
     *
     * This is the "fast" path, designed for non-suspending, main-thread-safe reads.
     *
     * 1.  It first attempts to find the value in the in-memory `cache`.
     * 2.  If the value is not in memory (a "cache miss"), it performs a **blocking** read
     *     from disk to fetch the value.
     * 3.  If found on disk, the value is then stored in the in-memory cache for subsequent
     *     fast reads before being returned.
     *
     * **Warning**: Because a cache miss can trigger a blocking disk I/O operation, this function
     * should be used judiciously on the main thread, especially during performance-critical moments
     * like app startup. It is best suited for hot paths where the data is expected to be in memory.
     * For general-purpose reads, prefer the suspending `getBytes` function.
     *
     * @param key The key to retrieve.
     * @return The `ByteArray` value if found in memory or on disk, otherwise `null`.
     */
    private fun getBytesFast(key: String): ByteArray? {
        cache[key]?.let { return it }

        val bytes = readFromDiskBlocking(key) ?: return null
        cache[key] = bytes
        return bytes
    }

    /**
     * Synchronously retrieves a String value.
     *
     * This is a "fast" path read. It first checks the in-memory cache.
     * If the value is not in memory, it will **block the calling thread** to read from disk.
     *
     * This is suitable for hot-path reads from the main thread *if and only if* you expect the
     * value to be in the in-memory cache. For all other cases, prefer the suspending [getString]
     * variant to avoid blocking the main thread.
     *
     * @param key The key for the value to retrieve.
     * @return The [String] value, or `null` if the key does not exist or the data is not a valid UTF-8 string.
     */
    fun getStringFast(key: String): String? =
        getBytesFast(key)?.toString(Charsets.UTF_8)

    /**
     * Reads an [Int] from the cache for the given [key].
     *
     * This is a "fast" read operation:
     * - It performs a non-suspending, in-memory lookup first.
     * - If the value is not in memory, it will **block the calling thread** to read from disk.
     * - This is suitable for use on the main thread only if the value is expected to be already in memory (hot path).
     *
     * @param key The key to retrieve the value for.
     * @return The stored [Int] value, or `null` if the key does not exist or the data is invalid.
     * @see getInt for the suspending (safer) version.
     */
    fun getIntFast(key: String): Int? =
        getBytesFast(key)?.let { ByteBuffer.wrap(it).int }

    /**
     * Retrieves a Long value from the cache.
     *
     * This is the "fast" version, which prioritizes speed. It attempts to read directly
     * from the in-memory map. If the value is not in memory, it will block the calling
     * thread to read from disk.
     *
     * - Non-suspending, but can cause disk I/O on the calling thread.
     * - Suitable for use cases where the value is expected to be hot (in memory)
     *   and you need immediate, non-coroutine access.
     * - Avoid calling this on the main thread if a cache miss is likely.
     *
     * @param key The key for the value to retrieve.
     * @return The Long value, or null if the key does not exist.
     */
    fun getLongFast(key: String): Long? =
        getBytesFast(key)?.let { ByteBuffer.wrap(it).long }

    /**
     * Retrieves a [Float] value from the cache for the given [key].
     * This is a **non-suspending** "fast path" read.
     *
     * - If the value is in the in-memory cache (hot), it is returned instantly.
     * - If the value is not in memory, this method will **block the calling thread**
     *   to read from disk. Avoid calling this on the main thread if you anticipate
     *   cache misses. For a safe, suspendable alternative, use [getFloat].
     *
     * @param key The key for the [Float] to retrieve.
     * @return The [Float] value if found, otherwise `null`.
     */
    fun getFloatFast(key: String): Float? =
        getBytesFast(key)?.let { ByteBuffer.wrap(it).float }

    /**
     * Synchronously retrieves a [Double] value from the cache for the given [key].
     * This is a "fast" operation that first checks the in-memory cache.
     * If the value is not in memory, it will perform a **blocking** disk read.
     * Avoid calling this on the main thread if you anticipate a cache miss.
     *
     * @param key The key to retrieve the value for.
     * @return The [Double] value if it exists, or `null` otherwise.
     */
    fun getDoubleFast(key: String): Double? =
        getBytesFast(key)?.let { ByteBuffer.wrap(it).double }

    /**
     * Retrieves a boolean value from the cache for the given [key].
     *
     * This is a "fast" operation that attempts to read from the in-memory cache first.
     * If the value is not in memory, it will **block the calling thread** to read from disk.
     *
     * This method is suitable for use on the main thread only if you are certain the cache
     * has been "warmed up" (i.e., the value is likely in memory). For guaranteed non-blocking
     * reads, use the suspending [getBoolean] function instead.
     *
     * @param key The key to retrieve.
     * @param default The default value to return if the key is not found or the stored value is malformed.
     * @return The boolean value associated with the key, or [default] if not found.
     */
    fun getBooleanFast(key: String, default: Boolean = false): Boolean {
        val b = getBytesFast(key)?.getOrNull(0)
        return when (b) {
            1.toByte() -> true
            0.toByte() -> false
            else -> default
        }
    }

    /**
     * Retrieves the raw [ByteArray] for a given [key], suspending if a disk read is needed.
     *
     * This method provides a safe way to read from the cache. It first checks the in-memory map.
     * If the key is not found in memory, it will suspend and attempt to read the value from disk.
     * If found on disk, the value is then loaded into the in-memory cache for subsequent fast access.
     * This function also handles lazy-reloading the entire cache from disk if it was previously
     * cleared due to system memory pressure.
     *
     * @param key The key for the data to retrieve.
     * @return The [ByteArray] associated with the key, or `null` if the key is not found in memory or on disk.
     */
    private suspend fun getBytes(key: String): ByteArray? = mutex.withLock {
        ensureLoadedLocked()
        cache[key] ?: readFromDiskSuspend(key)?.also { cache[key] = it }
    }

    /**
     * Reads a String value from the cache.
     *
     * This is a suspending function, safe to call from a coroutine. It will read from the
     * in-memory cache if available, otherwise it will load from disk.
     *
     * @param key The key to retrieve the value for.
     * @return The String value, or `null` if the key does not exist or the value is not a String.
     */
    suspend fun getString(key: String): String? =
        getBytes(key)?.toString(Charsets.UTF_8)

    /**
     * Retrieves an [Int] value from the cache.
     *
     * This is a suspending function that safely reads from the cache. It will first check
     * the in-memory cache and, if not found, will read from the encrypted disk store.
     * The operation is performed on an I/O dispatcher.
     *
     * @param key The key associated with the value.
     * @return The [Int] value, or `null` if the key does not exist or the value is not a valid integer.
     */
    suspend fun getInt(key: String): Int? =
        getBytes(key)?.let { ByteBuffer.wrap(it).int }

    /**
     * Asynchronously retrieves a [Long] value from the cache.
     *
     * This is a suspending function that ensures the in-memory cache is loaded from disk
     * if it was previously cleared due to memory pressure, before attempting to read the value.
     * It provides a safe way to read data that may not be in the hot-memory cache.
     *
     * @param key The key to retrieve the value for.
     * @return The [Long] value associated with the key, or `null` if the key does not exist.
     */
    suspend fun getLong(key: String): Long? =
        getBytes(key)?.let { ByteBuffer.wrap(it).long }

    /**
     * Retrieves a Float value from the cache for the given [key], or null if it doesn't exist.
     * This is a suspending function that may perform disk I/O.
     * For a non-suspending, memory-only alternative, see [getFloatFast].
     *
     * @param key The key to retrieve the value for.
     * @return The Float value, or null if not found.
     */
    suspend fun getFloat(key: String): Float? =
        getBytes(key)?.let { ByteBuffer.wrap(it).float }

    /**
     * Retrieves a [Double] from the cache for the given [key].
     *
     * This is a suspending function that performs I/O and should be called from a coroutine.
     * It ensures the cache is loaded from disk if it was previously cleared due to memory pressure.
     *
     * For a non-suspending, memory-only version, see [getDoubleFast].
     *
     * @param key The key to retrieve the value for.
     * @return The [Double] value, or `null` if the key is not found.
     */
    suspend fun getDouble(key: String): Double? =
        getBytes(key)?.let { ByteBuffer.wrap(it).double }

    /**
     * Retrieves a boolean value from the cache for the given [key].
     *
     * This is a suspending function that safely handles disk I/O on a background thread.
     * It first checks the in-memory cache, and if not found, reads from the encrypted disk store.
     *
     * @param key The key for the boolean value to retrieve.
     * @param default The default value to return if the key is not found in the cache. Defaults to `false`.
     * @return The boolean value associated with the [key], or [default] if the key does not exist.
     */
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean {
        val b = getBytes(key)?.getOrNull(0)
        return when (b) {
            1.toByte() -> true
            0.toByte() -> false
            else -> default
        }
    }

    /**
     * Saves a String value.
     *
     * This operation is thread-safe and respects the configured [PersistenceMode].
     * Passing `null` for the value will remove the key.
     *
     * @param key The key to associate with the value.
     * @param value The String value to save, or `null` to remove the key.
     */
    suspend fun putString(key: String, value: String?) =
        putBytes(key, value?.toByteArray(Charsets.UTF_8))

    /**
     * Asynchronously saves an integer value to the cache.
     * The value is associated with the given [key].
     * This operation is thread-safe and respects the configured [PersistenceMode].
     *
     * @param key The key to associate the value with.
     * @param value The integer value to save.
     */
    suspend fun putInt(key: String, value: Int) =
        putBytes(key, ByteBuffer.allocate(4).putInt(value).array())

    /**
     * Saves a [Long] value to the cache.
     *
     * This is a suspending function, safe to call from any coroutine. The operation is
     * thread-safe and respects the configured [PersistenceMode].
     *
     * @param key The key to associate with the value.
     * @param value The [Long] value to save.
     */
    suspend fun putLong(key: String, value: Long) =
        putBytes(key, ByteBuffer.allocate(8).putLong(value).array())

    /**
     * Saves a [Float] value to the cache.
     *
     * This is a suspending function, ensuring that any disk I/O is performed safely
     * off the main thread. The write behavior is governed by the configured [PersistenceMode].
     *
     * @param key The key to associate with the value.
     * @param value The [Float] value to save.
     */
    suspend fun putFloat(key: String, value: Float) =
        putBytes(key, ByteBuffer.allocate(4).putFloat(value).array())

    /**
     * Saves a [Double] value to the cache.
     *
     * This is a suspending function, safe to call from any coroutine. The write operation
     * respects the configured [PersistenceMode], meaning it might be debounced
     * ([PersistenceMode.WRITE_BACK]) or written immediately ([PersistenceMode.WRITE_THROUGH]).
     *
     * @param key The key to associate with the value.
     * @param value The [Double] value to save.
     */
    suspend fun putDouble(key: String, value: Double) =
        putBytes(key, ByteBuffer.allocate(8).putDouble(value).array())

    /**
     * Saves a [Boolean] value to the cache.
     *
     * This is a suspending function, ensuring that any disk I/O happens on a background thread.
     * The write behavior (immediate or debounced) is determined by the configured [PersistenceMode].
     *
     * @param key The key to associate with the boolean value.
     * @param value The [Boolean] value to save. `true` is stored as `1` and `false` as `0`.
     */
    suspend fun putBoolean(key: String, value: Boolean) =
        putBytes(key, byteArrayOf(if (value) 1 else 0))

    /**
     * Performs a **blocking** read from the encrypted disk store to retrieve the value for a specific [key].
     *
     * This function is intentionally designed to block the calling thread, making it suitable
     * for the "fast path" `get...Fast()` methods where an immediate, synchronous result is required
     * after an in-memory cache miss. It uses `runBlocking` with the configured I/O dispatcher
     * to execute the disk read.
     *
     * **Warning:** This should never be called directly from the main thread or within a
     * suspend function. It is an internal utility for `getBytesFast`.
     *
     * @param key The key of the value to read from disk.
     * @return The `ByteArray` value if found on disk, otherwise `null`.
     */
    private fun readFromDiskBlocking(key: String): ByteArray? {
        // Fast path is allowed to block by design
        return runBlocking(config.ioDispatcher) {
            store.read()[key]
        }
    }

    /**
     * Asynchronously reads a single key-value pair directly from the disk store.
     * This is a suspending function, intended to be called from a coroutine.
     * It reads the entire cache file from disk, decrypts it, and then looks up the specific key.
     *
     * @param key The key to retrieve from the disk.
     * @return The `ByteArray` value associated with the key if found on disk, otherwise `null`.
     */
    private suspend fun readFromDiskSuspend(key: String): ByteArray? {
        return store.read()[key]
    }

    /**
     * Removes the key-value pair associated with the given [key].
     *
     * This is a suspending function that safely handles concurrency. The removal
     * will be persisted to disk according to the configured [PersistenceMode].
     *
     * @param key The key to remove from the cache.
     */
    suspend fun remove(key: String) = mutex.withLock {
        ensureLoadedLocked()
        cache.remove(key)
        onMutationLocked()
    }

    /**
     * Immediately writes all in-memory changes to disk.
     *
     * This function is useful in scenarios where you need to guarantee that the latest data is
     * persisted, such as before a user logs out, during app shutdown, or in tests.
     *
     * If the cache is configured for `PersistenceMode.WRITE_BACK`, this will cancel any
     * scheduled (debounced) write and perform it immediately. If configured for
     * `PersistenceMode.WRITE_THROUGH`, this operation has no effect as writes are already immediate.
     */
    suspend fun flush() = mutex.withLock {
        ensureLoadedLocked()
        cancelPendingFlushLocked()
        persistSnapshotLocked()
    }

    /**
     * Clears all data from both the in-memory cache and the encrypted disk store.
     * This operation is irreversible.
     *
     * It performs the following actions:
     * 1. Cancels any pending write operations to prevent stale data from being saved.
     * 2. Clears the in-memory map.
     * 3. Deletes the underlying encrypted cache file from disk.
     */
    suspend fun clear() = mutex.withLock {
        cancelPendingFlushLocked()
        cache.clear()
        memoryCleared = false
        store.clear()
        config.logger.d("Cleared cache")
    }

    /**
     * Shuts down the cache, releasing resources. This is an optional but recommended cleanup step.
     *
     * This method performs the following actions:
     * 1. Unregisters system memory pressure callbacks.
     * 2. Cancels any pending write-back flush operations.
     * 3. Cancels all background tasks running in the internal coroutine scope.
     * 4. Resets the `initialized` flag, allowing `init` to be called again.
     *
     * It is safe to call `init()` again after `shutdown()` to re-initialize the cache.
     *
     * **When to call:**
     * - In `Application.onTerminate()` for emulators or debug builds. Note that `onTerminate` is
     *   not guaranteed to be called on production devices.
     * - During explicit user-driven "sign out" or "clear data" flows where you want to tear down
     *   the cache instance completely.
     * - In unit tests during `tearDown` to ensure a clean state between tests.
     *
     * @param context The context used to unregister component callbacks.
     */
    fun shutdown(context: Context) {
        callbacks?.let { context.unregisterComponentCallbacks(it) }
        callbacks = null

        flushJob?.cancel()
        flushJob = null

        // Never cancel the scope itself — system callbacks may still fire.
        scope.coroutineContext.cancelChildren()

        initialized = false
    }


    /**
     * The core suspend function for writing data. All public `put` methods delegate to this.
     * It ensures thread-safe modification of the cache and handles the persistence strategy.
     *
     * 1.  Acquires a lock to ensure atomic operation.
     * 2.  Ensures the in-memory cache is loaded from disk if it was previously cleared.
     * 3.  Updates the in-memory `cache` with the new value. If `value` is `null`, the key is removed.
     * 4.  Triggers the persistence mechanism (`onMutationLocked`), which will either flush
     *     immediately (write-through) or schedule a delayed flush (write-back).
     *
     * @param key The key to associate with the data.
     * @param value The `ByteArray` to store. If `null`, the entry for the given `key` will be removed.
     */
    private suspend fun putBytes(key: String, value: ByteArray?) = mutex.withLock {
        ensureLoadedLocked()
        if (value == null) cache.remove(key) else cache[key] = value
        onMutationLocked()
    }

    /**
     * Called after any mutation to the in-memory cache (`put`, `remove`).
     * This function is responsible for triggering the persistence logic based on the configured
     * [PersistenceMode]. It must be called from within a `mutex.withLock` block.
     *
     * - In [PersistenceMode.WRITE_BACK] mode, it schedules a debounced flush to disk.
     * - In [PersistenceMode.WRITE_THROUGH] mode, it cancels any pending flush and immediately
     *   launches a coroutine to persist the current cache state to disk.
     */
    private fun onMutationLocked() {
        when (config.persistenceMode) {
            PersistenceMode.WRITE_BACK -> scheduleFlushLocked()
            PersistenceMode.WRITE_THROUGH -> {
                cancelPendingFlushLocked()
                // Commit immediately from IO dispatcher
                scope.launch {
                    mutex.withLock { persistSnapshotLocked() }
                }
            }
        }
    }

    /**
     * Schedules a debounced flush to disk for a write-back cache.
     *
     * This function is called after a mutation when the `persistenceMode` is [PersistenceMode.WRITE_BACK].
     * It cancels any previously scheduled flush and starts a new one with a delay specified by
     * [Config.debounceMs]. This ensures that rapid, successive writes only result in a single
     * disk write operation after the activity settles down.
     *
     * The actual persistence happens in [persistSnapshotLocked] after the delay.
     * Must be called within a `mutex` lock.
     */
    private fun scheduleFlushLocked() {
        val debounce = max(0L, config.debounceMs)

        cancelPendingFlushLocked()
        flushJob = scope.launch {
            delay(debounce)
            mutex.withLock { persistSnapshotLocked() }
        }
    }

    private fun cancelPendingFlushLocked() {
        flushJob?.cancel()
        flushJob = null
    }

    private suspend fun persistSnapshotLocked() {
        val snapshot: Map<String, ByteArray> = HashMap(cache)
        config.logger.d("Persisting snapshot (entries=${snapshot.size})")
        store.write(snapshot)
    }

    /**
     * If memory was cleared due to pressure, we reload from disk on the next suspend call.
     * Hot reads will return null until a reload happens (by design).
     */
    private suspend fun ensureLoadedLocked() {
        if (!memoryCleared) return
        config.logger.d("Reloading from disk after memory trim")
        cache.clear()
        cache.putAll(store.read())
        memoryCleared = false
    }

    /**
     * Performs a one-time migration from a specified `SharedPreferences` file.
     * This function reads all key-value pairs, converts them to byte arrays,
     * and stores them in the `DroidCache`. After a successful migration, the
     * original `SharedPreferences` file is cleared to prevent re-migration.
     *
     * The migration supports common primitive types: `String`, `Boolean`, `Int`,
     * `Long`, `Double`, and `Float`. Other types in SharedPreferences will be ignored.
     *
     * This operation is atomic and durable: all migrated data is immediately flushed
     * to the encrypted disk cache. If the process is interrupted, it can be safely
     * re-run, although already migrated data will be overwritten.
     *
     * @param context The application context, used to access `SharedPreferences`.
     * @param spName The name of the `SharedPreferences` file to migrate from.
     */
    private suspend fun migrateFromSharedPreferences(context: Context, spName: String) = mutex.withLock {
        val sp = context.getSharedPreferences(spName, Context.MODE_PRIVATE)
        val all = sp.all
        if (all.isEmpty()) return

        config.logger.d("Migrating SharedPreferences($spName) → DroidCache (${all.size} keys)")

        for ((key, value) in all) {
            when (value) {
                is String -> cache[key] = value.toByteArray(Charsets.UTF_8)
                is Boolean -> cache[key] = byteArrayOf(if (value) 1 else 0)
                is Int -> cache[key] = ByteBuffer.allocate(4).putInt(value).array()
                is Long -> cache[key] = ByteBuffer.allocate(8).putLong(value).array()
                is Double -> cache[key] = ByteBuffer.allocate(8).putDouble(value).array()
                is Float -> cache[key] = ByteBuffer.allocate(4).putFloat(value).array()
                // If you need: Float, Set<String>, etc - add here.
            }
        }

        // Persist immediately (migration should be durable).
        cancelPendingFlushLocked()
        persistSnapshotLocked()

        sp.edit { clear() }
        config.logger.d("Migration complete; old SharedPreferences cleared")
    }

    /**
     * Registers system memory callbacks to handle low-memory situations.
     *
     * Based on the provided [MemoryPolicy], this function sets up a [ComponentCallbacks2]
     * listener. When the system reports memory pressure at or above the configured
     * `criticalTrimLevel`, it triggers a process to clear the in-memory cache.
     *
     * This helps the app be a good citizen by releasing memory when the OS needs it.
     * The on-disk cache remains intact. The in-memory cache will be lazily reloaded
     * from disk on the next suspending cache operation.
     *
     * @param appContext The context used to register the component callbacks.
     */
    private fun registerMemoryCallbacks(appContext: Context) {
        val policy = config.memoryPolicy
        val cb = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                // Treat as critical pressure.
                handleTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }

            override fun onTrimMemory(level: Int) {
                handleTrim(level)
            }

            private fun handleTrim(level: Int) {
                if (!policy.clearMemoryOnCriticalTrim) return
                if (level < policy.criticalTrimLevel) return

                scope.launch {
                    mutex.withLock {
                        config.logger.d("Memory trim (level=$level); clearing in-memory cache")
                        if (policy.flushBeforeClear) {
                            // Best-effort: flush pending changes first.
                            runCatching {
                                cancelPendingFlushLocked()
                                persistSnapshotLocked()
                            }
                        }
                        cache.clear()
                        // Mark for lazy reload on next suspend API call.
                        memoryCleared = true
                    }
                }
            }
        }

        callbacks = cb
        appContext.registerComponentCallbacks(cb)
    }

    // ------------------ TEST HOOKS ------------------

    /**
     * Resets the entire state of the cache for testing purposes.
     * This is a destructive operation designed to provide a clean slate between tests.
     *
     * It performs the following actions:
     * 1.  Cancels any pending write-back flush jobs.
     * 2.  Clears the in-memory map (`cache`).
     * 3.  Resets the `memoryCleared` flag.
     * 4.  If the underlying file store is initialized, it clears all data on disk.
     * 5.  If the cache was initialized, it calls [shutdown] to unregister callbacks
     *     and clean up resources.
     *
     * @param context The context, required for the [shutdown] process.
     */
    @VisibleForTesting
    internal suspend fun resetForTests(context: Context) {
        mutex.withLock {
            flushJob?.cancel()
            flushJob = null

            cache.clear()
            memoryCleared = false

            if (::store.isInitialized) {
                store.clear()
            }
        }

        if (initialized) {
            shutdown(context)
        }
    }


    /**
     * Test-only hook to simulate a memory trim event at a specific [level].
     *
     * This function mimics the behavior of the internal `handleTrim` logic, allowing tests to
     * verify how the cache responds to memory pressure without needing to rely on the
     * Android framework to trigger a real `onTrimMemory` event.
     *
     * If the provided [level] meets or exceeds the configured `criticalTrimLevel` and the
     * policy `clearMemoryOnCriticalTrim` is enabled, the cache will:
     * 1. Optionally flush any pending writes to disk (`flushBeforeClear`).
     * 2. Clear the in-memory map.
     * 3. Set a flag (`memoryCleared`) to force a lazy reload from disk on the next suspend operation.
     *
     * @param level The simulated trim level, corresponding to `ComponentCallbacks2.TRIM_MEMORY_*` constants.
     */
    @VisibleForTesting
    internal suspend fun triggerTrimForTests(level: Int) {
        mutex.withLock {
            if (config.memoryPolicy.clearMemoryOnCriticalTrim &&
                level >= config.memoryPolicy.criticalTrimLevel
            ) {
                config.logger.d("Test-triggered memory trim (level=$level)")
                if (config.memoryPolicy.flushBeforeClear) {
                    cancelPendingFlushLocked()
                    persistSnapshotLocked()
                }
                cache.clear()
                memoryCleared = true
            }
        }
    }
}
