package com.rommansabbir.droidcache

import android.util.Log

/**
 * Minimal logging abstraction so apps can plug in their own logger.
 */
fun interface CacheLogger {
    fun d(message: String)
}

/**
 * Default Android logger.
 */
class AndroidCacheLogger(
    private val enabled: Boolean,
    private val tag: String
) : CacheLogger {
    override fun d(message: String) {
        if (enabled) Log.d(tag, message)
    }
}

/**
 * No-op logger.
 */
object NoopCacheLogger : CacheLogger {
    override fun d(message: String) = Unit
}
