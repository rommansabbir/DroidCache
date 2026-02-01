# DroidCache

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

DroidCache is a powerful, encrypted, and coroutine-based key-value cache for modern Android development. It provides a simple and efficient way to store and retrieve data while ensuring thread safety and high performance.

## Features

- **Encrypted Storage:** All data is encrypted using AES-GCM via the AndroidKeyStore, providing a secure storage solution.
- **Coroutine-Based:** Asynchronous operations are handled using Kotlin Coroutines, preventing blocking of the main thread.
- **In-Memory Hot Reads:** Frequently accessed data is cached in memory for near-instant retrieval.
- **Crash-Safe Writes:** A backup and recovery mechanism ensures data integrity even in the event of a crash.
- **Flexible Persistence:** Choose between `WRITE_BACK` (debounced) and `WRITE_THROUGH` (immediate) persistence modes.
- **SharedPreferences Migration:** A simple, one-time migration from existing `SharedPreferences` is supported.
- **Memory Pressure Handling:** The cache can automatically respond to system memory pressure to reduce its memory footprint.

## Installation

To use DroidCache in your project, add the following to your app's `build.gradle.kts` file:

```kotlin
dependencies {
    implementation(project(":droidcache"))
}
```

## Usage

### Initialization

Initialize DroidCache in your `Application.onCreate` method:

```kotlin
import com.rommansabbir.droidcache.DroidCache
import com.rommansabbir.droidcache.AndroidCacheLogger
import kotlinx.coroutines.launch

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
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
    }
}
```

### Reading and Writing Data

DroidCache provides two ways to read and write data:

- **Fast (non-suspending):** These methods provide a fast, non-suspending way to access the in-memory cache. If the data is not in memory, a blocking disk read will be performed.

  ```kotlin
  // Read from the cache
  val userId = DroidCache.getStringFast("USER_ID")
  
  // Write to the cache
  DroidCache.putString("USER_ID", "12345")
  ```

- **Suspending:** These methods are safe to call from any coroutine and will not block the main thread.

  ```kotlin
  // Read from the cache
  val authToken = DroidCache.getString("AUTH_TOKEN")
  
  // Write to the cache
  DroidCache.putString("AUTH_TOKEN", "abcdef123456")
  ```

### Supported Data Types

DroidCache supports the following data types:

- `String`
- `Int`
- `Long`
- `Float`
- `Double`
- `Boolean`
- `ByteArray`

## Configuration

DroidCache can be configured during initialization using the `DroidCache.Config` data class. The following options are available:

| Parameter | Description |
|---|---|
| `fileName` | The name of the file on disk where the cache will be stored. |
| `persistenceMode` | The strategy for writing changes to disk (`WRITE_BACK` or `WRITE_THROUGH`). |
| `debounceMs` | The delay in milliseconds before flushing changes to disk in `WRITE_BACK` mode. |
| `logger` | A custom logger for internal cache events. |
| `migration` | Configuration for one-time migration from `SharedPreferences`. |
| `memoryPolicy` | Configuration for how the cache responds to system memory pressure. |
| `ioDispatcher` | The `CoroutineDispatcher` for disk I/O and cryptographic operations. |
| `cryptoEngine` | The engine used for encryption and decryption. |

## Migration from SharedPreferences

DroidCache can perform a one-time migration from `SharedPreferences`. To enable this, configure the `migration` property during initialization:

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

## Testing

DroidCache provides the following functions for testing:

- `resetForTests(context: Context)`: Clears all data and resets the cache's state between tests.
- `triggerTrimForTests(level: Int)`: Simulates a memory pressure event.

## License

```
MIT License

Copyright (c) 2024

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
