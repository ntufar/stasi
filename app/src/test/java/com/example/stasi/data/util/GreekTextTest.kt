package com.example.stasi.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreekTextTest {

    @Test
    fun normalizeGreek_accentInsensitiveGreekMatches_specStyleSyntagma() {
        // Spec acceptance: Greek search should match despite accents/case.
        val query = normalizeGreek("Σύνταγμα")
        val stopDescr = normalizeGreek("ΣΥΝΤΑΓΜΑ")
        assertEquals(query, stopDescr)
        assertTrue(stopDescr.contains(normalizeGreek("ταγμα")))
    }

    @Test
    fun normalizeGreek_stripsCombiningMarks() {
        assertEquals("αυτο", normalizeGreek("αὐτό"))
    }

    @Test
    fun normalizeGreek_trimsAndLowercases() {
        assertEquals("foo", normalizeGreek("  FOO  "))
    }
}
