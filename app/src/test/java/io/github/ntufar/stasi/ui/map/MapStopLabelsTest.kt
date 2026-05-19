package io.github.ntufar.stasi.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapStopLabelsTest {

    @Test
    fun truncateStopMapLabel_shortTextUnchanged() {
        assertEquals("Σύνταγμα", truncateStopMapLabel("  Σύνταγμα  "))
    }

    @Test
    fun truncateStopMapLabel_longTextEllipsized() {
        val long = "A".repeat(40)
        val result = truncateStopMapLabel(long)
        assertEquals(22, result.length)
        assertEquals('…', result.last())
    }
}
