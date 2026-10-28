package com.iskezis.myst.util

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

fun sha1Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

fun normalizeText(value: String): String {
    val lowered = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    return lowered.replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun similarity(a: String, b: String): Double {
    val first = normalizeText(a)
    val second = normalizeText(b)
    if (first.isEmpty() || second.isEmpty()) return 0.0
    if (first == second) return 1.0
    val max = maxOf(first.length, second.length)
    val distance = levenshtein(first, second)
    return (1.0 - distance.toDouble() / max).coerceIn(0.0, 1.0)
}

fun levenshtein(first: String, second: String): Int {
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length
    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    first.forEachIndexed { i, c1 ->
        current[0] = i + 1
        second.forEachIndexed { j, c2 ->
            val insert = current[j] + 1
            val delete = previous[j + 1] + 1
            val replace = previous[j] + if (c1 == c2) 0 else 1
            current[j + 1] = minOf(insert, delete, replace)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length]
}

fun formatDuration(millis: Long): String {
    if (millis <= 0) return "--:--"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
