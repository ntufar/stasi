package io.github.ntufar.stasi.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointRateLimiterTest {

    @Test
    fun gateWebGetRoutes_includesTrimmedLineCode() {
        assertEquals(
            "webGetRoutes::750",
            EndpointRateLimiter.gateWebGetRoutes(" 750 "),
        )
    }

    @Test
    fun gateWebGetStops_includesTrimmedRouteCode() {
        assertEquals(
            "webGetStops::2033",
            EndpointRateLimiter.gateWebGetStops(" 2033 "),
        )
    }
}
