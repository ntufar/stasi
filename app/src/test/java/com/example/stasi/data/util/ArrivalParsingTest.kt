package com.example.stasi.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalParsingTest {

    @Test
    fun parseArrivalMinutes_numericString() {
        assertEquals(5, parseArrivalMinutes("5"))
    }

    @Test
    fun parseArrivalMinutes_extractsDigitsFromMixed() {
        assertEquals(12, parseArrivalMinutes("~12 min"))
    }

    @Test
    fun parseArrivalMinutes_emptyOrNoDigits_returnsUnknown() {
        assertEquals(ARRIVAL_MINUTES_UNKNOWN, parseArrivalMinutes(null))
        assertEquals(ARRIVAL_MINUTES_UNKNOWN, parseArrivalMinutes(""))
        assertEquals(ARRIVAL_MINUTES_UNKNOWN, parseArrivalMinutes("soon"))
    }
}
