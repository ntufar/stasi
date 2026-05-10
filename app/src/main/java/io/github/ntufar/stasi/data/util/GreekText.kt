package io.github.ntufar.stasi.data.util

import java.text.Normalizer

/**
 * When the query is Latin letters only (typical Greeklish), map each letter to a rough Greek
 * equivalent so local search can match OASA Greek stop/line names (see SPEC acceptance: "syntagma").
 * Mixed alphanumeric queries (e.g. line codes) are left unchanged.
 */
fun expandLatinLettersQueryForGreekSearch(input: String): String {
    val t = input.trim()
    if (t.length < 2) return input
    if (!t.all { it.isLetter() && it.code < 0x80 }) return input
    return buildString(t.length) {
        for (ch in t) {
            val lower = ch.lowercaseChar()
            append(LATIN_TO_GREEK_ROUGH[lower] ?: ch)
        }
    }
}

/** Single-letter Greeklish → Greek (phonetic-ish); digraphs not handled. */
private val LATIN_TO_GREEK_ROUGH: Map<Char, Char> =
    mapOf(
        'a' to 'α',
        'b' to 'β',
        'c' to 'κ',
        'd' to 'δ',
        'e' to 'ε',
        'f' to 'φ',
        'g' to 'γ',
        'h' to 'η',
        'i' to 'ι',
        'j' to 'γ',
        'k' to 'κ',
        'l' to 'λ',
        'm' to 'μ',
        'n' to 'ν',
        'o' to 'ο',
        'p' to 'π',
        'q' to 'κ',
        'r' to 'ρ',
        's' to 'σ',
        't' to 'τ',
        'u' to 'υ',
        'v' to 'β',
        'w' to 'ω',
        'x' to 'ξ',
        'y' to 'υ',
        'z' to 'ζ',
    )

/** Spec §10: strip combining marks for fuzzy Greek match. */
fun normalizeGreek(input: String): String {
    val nfd = Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
    return nfd.replace("\\p{M}+".toRegex(), "").lowercase()
}

/** Line public number / internal code + description so queries like "224" match. */
fun lineSearchNorm(lineId: String, lineCode: String, descr: String): String =
    normalizeGreek(
        listOf(lineId, lineCode, descr).filter { it.isNotBlank() }.joinToString(" "),
    )

/** Stop code + description so numeric stop IDs are searchable. */
fun stopSearchNorm(stopCode: String, descr: String): String =
    normalizeGreek(
        listOf(stopCode, descr).filter { it.isNotBlank() }.joinToString(" "),
    )
