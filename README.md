# DroidCache

DroidCache is an encrypted, coroutine-backed, file-based key-value cache for Android.

## Features

- ✅ No main-thread disk/crypto
- ✅ In-memory hot reads
- ✅ AES-GCM encryption via AndroidKeyStore
- ✅ Crash-safe write with .bak recovery
- ✅ Write-back (debounced) or Write-through (immediate)
- ✅ SharedPreferences migration (optional)
- ✅ Memory pressure handling (optional)

## Usage

### Initialization

In your `Application.onCreate`, initialize the cache:

```kotlin
// Application.onCreate
lifecycleScope.launch {
  DroidCache.init(
    appContext = applicationContext,
    config = DroidCache.Config(
      persistenceMode = DroidCache.PersistenceMode.WRITE_BACK,
      debounceMs = 500,
      logger = AndroidCacheLogger(BuildConfig.DEBUG, "DroidCache")
    )
  )
}
```

### Reading and Writing Data

- **Hot path read (memory-only, non-suspending):**
  ```kotlin
  val uid = DroidCache.getStringFast("USER_ID")
  ```

- **Safe suspend read/write:**
  ```kotlin
  val token = DroidCache.getString("TOKEN")
  DroidCache.putString("TOKEN", "abc")
  ```

## Configuration

You can configure `DroidCache` during initialization using the `DroidCache.Config` data class.

- `fileName`: The name of the file on disk where the cache will be stored.
- `persistenceMode`: The strategy for writing changes to disk (`WRITE_BACK` or `WRITE_THROUGH`).
- `debounceMs`: The delay in milliseconds before flushing changes to disk in `WRITE_BACK` mode.
- `logger`: A custom logger for internal cache events.
- `migration`: Configuration for one-time migration from `SharedPreferences`.
- `memoryPolicy`: Configuration for how the cache responds to system memory pressure.
- `ioDispatcher`: The `CoroutineDispatcher` for disk I/O and cryptographic operations.
- `cryptoEngine`: The engine used for encryption and decryption.

## Migration from SharedPreferences

`DroidCache` can perform a one-time migration from `SharedPreferences`. To enable this, configure the `migration` property during initialization:

```kotlin
DroidCache.init(
  appContext = applicationContext,
  config = DroidCache.Config(
    migration = DroidCache.MigrationConfig(
      enabled = true,
      sharedPreferencesName = "YOUR_SP_NAME"
    )
  )
)
```

## Memory Management

`DroidCache` can be configured to respond to system memory pressure by clearing the in-memory cache. This is enabled by default. You can customize this behavior using the `memoryPolicy` configuration.

## Testing

`DroidCache` provides a `resetForTests` function to clear all data and reset the cache's state between tests. There is also a `triggerTrimForTests` to simulate memory pressure.
