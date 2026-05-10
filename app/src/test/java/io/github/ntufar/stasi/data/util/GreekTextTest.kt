package io.github.ntufar.stasi.data.util

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

    @Test
    fun lineSearchNorm_includesPublicLineNumberForNumericQueries() {
        val norm = lineSearchNorm("224", "224-xyz", "ΠΕΙΡΑΙΑΣ - ΚΕΝΤΡΟ")
        assertTrue(norm.contains("224"))
        assertTrue(norm.contains(normalizeGreek("πειραιας")))
    }

    @Test
    fun stopSearchNorm_includesStopCode() {
        val norm = stopSearchNorm("07001", "ΣΥΝΤΑΓΜΑ")
        assertTrue(norm.contains("07001"))
        assertTrue(norm.contains(normalizeGreek("συνταγμα")))
    }

    @Test
    fun expandLatinLettersQuery_mapsSyntagmaToGreekForNormMatch() {
        val expanded = expandLatinLettersQueryForGreekSearch("syntagma")
        assertEquals(normalizeGreek("συνταγμα"), normalizeGreek(expanded))
    }

    @Test
    fun expandLatinLettersQuery_mapsNosokPrefixForHospitalGreeklish() {
        val expanded = expandLatinLettersQueryForGreekSearch("nosok")
        assertTrue(normalizeGreek("ΝΟΣΟΚΟΜΕΙΟ").contains(normalizeGreek(expanded)))
    }

    @Test
    fun expandLatinLettersQuery_leavesNumericLineCodesUnchanged() {
        assertEquals("224", expandLatinLettersQueryForGreekSearch("224"))
    }

    @Test
    fun expandLatinLettersQuery_leavesMixedAlphanumericUnchanged() {
        assertEquals("X93", expandLatinLettersQueryForGreekSearch("X93"))
    }
}
