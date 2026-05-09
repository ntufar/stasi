package com.example.stasi.data.util

import java.text.Normalizer

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
