package com.rommansabbir.droidcachedemoapp

import android.app.Application
import com.rommansabbir.droidcache.AndroidCacheLogger
import com.rommansabbir.droidcache.DroidCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplications  : Application(){

    override fun onCreate() {
        super.onCreate()

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            DroidCache.init(
                appContext = applicationContext,
                config = DroidCache.Config(
                    persistenceMode = DroidCache.PersistenceMode.WRITE_BACK,
                    debounceMs = 500,
                    logger = AndroidCacheLogger(true, "DroidCache"),
                    memoryPolicy = DroidCache.MemoryPolicy(),
                )
            )
        }

    }
}