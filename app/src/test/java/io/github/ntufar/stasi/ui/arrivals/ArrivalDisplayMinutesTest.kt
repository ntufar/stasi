package io.github.ntufar.stasi.ui.arrivals

import io.github.ntufar.stasi.data.util.ARRIVAL_MINUTES_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalDisplayMinutesTest {

    @Test
    fun unknownMinutesUnchanged() {
        assertEquals(
            ARRIVAL_MINUTES_UNKNOWN,
            effectiveMinutesSinceSnapshot(
                ARRIVAL_MINUTES_UNKNOWN,
                1_000L,
                999_000L,
            ),
        )
    }

    @Test
    fun noSnapshotUsesRaw() {
        assertEquals(12, effectiveMinutesSinceSnapshot(12, null, 500_000L))
    }

    @Test
    fun subtractsWholeMinutesElapsed() {
        val snap = 1_000_000L
        assertEquals(
            10,
            effectiveMinutesSinceSnapshot(12, snap, snap + 2 * 60_000L + 30_000L),
        )
    }

    @Test
    fun neverNegative() {
        val snap = 1_000_000L
        assertEquals(
            0,
            effectiveMinutesSinceSnapshot(2, snap, snap + 5 * 60_000L),
        )
    }
}
