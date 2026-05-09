package com.example.stasi.data.util

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side politeness between OASA calls. Spec suggests 5s; a shorter interval keeps UX usable.
 */
class EndpointRateLimiter(private val minIntervalMs: Long = 1_200L) {
    private val mutex = Mutex()
    private var lastEnd = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val wait = lastEnd + minIntervalMs - now
        if (wait > 0) delay(wait)
        return try {
            block()
        } finally {
            lastEnd = SystemClock.elapsedRealtime()
        }
    }
}
