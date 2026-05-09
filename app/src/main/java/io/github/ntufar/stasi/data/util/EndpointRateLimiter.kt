package io.github.ntufar.stasi.data.util

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side politeness: separate pacing per API action so e.g. [EP_WEB_GET_ROUTES]
 * does not block [EP_WEB_GET_STOPS] (matches spec: limit per endpoint, not one global queue).
 */
class EndpointRateLimiter(private val minIntervalMs: Long = 1_200L) {

    private class Gate {
        val mutex = Mutex()
        var lastEnd = 0L
    }

    private val gates = ConcurrentHashMap<String, Gate>()

    private fun gate(key: String): Gate = gates.getOrPut(key) { Gate() }

    suspend fun <T> run(endpointKey: String, block: suspend () -> T): T {
        val g = gate(endpointKey)
        return g.mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val wait = g.lastEnd + minIntervalMs - now
            if (wait > 0) delay(wait)
            try {
                block()
            } finally {
                g.lastEnd = SystemClock.elapsedRealtime()
            }
        }
    }

    companion object {
        const val EP_WEB_GET_LINES = "webGetLines"
        const val EP_WEB_GET_ROUTES = "webGetRoutes"
        const val EP_WEB_GET_STOPS = "webGetStops"
        const val EP_GET_STOP_XY = "getStopNameAndXY"
        const val EP_GET_ARRIVALS = "getStopArrivals"
        const val EP_WEB_ROUTES_FOR_STOP = "webRoutesForStop"
        const val EP_BUS_LOCATION = "getBusLocation"
        const val EP_CLOSEST_STOPS = "getClosestStops"

        /** One pacing bucket per line so catalog sync does not serialize every line onto one queue. */
        fun gateWebGetRoutes(lineCode: String): String =
            "$EP_WEB_GET_ROUTES::${lineCode.trim()}"

        /** One bucket per route so background ingest does not block the user's route fetch. */
        fun gateWebGetStops(routeCode: String): String =
            "$EP_WEB_GET_STOPS::${routeCode.trim()}"
    }
}
