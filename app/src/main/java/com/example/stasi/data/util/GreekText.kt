package com.example.stasi.data.util

import java.text.Normalizer

/** Spec §10: strip combining marks for fuzzy Greek match. */
fun normalizeGreek(input: String): String {
    val nfd = Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
    return nfd.replace("\\p{M}+".toRegex(), "").lowercase()
}
