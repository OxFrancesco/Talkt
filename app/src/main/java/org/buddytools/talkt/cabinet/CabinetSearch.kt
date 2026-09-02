package org.buddytools.talkt.cabinet

import java.text.Normalizer
import java.util.Locale
import org.buddytools.talkt.data.TranscriptEntry

/** Ranked, typo-tolerant search across the local note and its optional reply metadata. */
internal fun fuzzySearchEntries(entries: List<TranscriptEntry>, rawQuery: String): List<TranscriptEntry> {
    val query = rawQuery.normalizedSearchText()
    if (query.isBlank()) return entries
    val queryTokens = query.split(' ').filter(String::isNotBlank)

    return entries.mapIndexedNotNull { index, entry ->
        val candidate = listOfNotNull(entry.text, entry.reply, entry.source, entry.kind)
            .joinToString(" ")
            .normalizedSearchText()
        val tokenScores = queryTokens.map { token -> fuzzyTokenScore(token, candidate) ?: return@mapIndexedNotNull null }
        val phraseBonus = if (candidate.contains(query)) -100 else 0
        RankedEntry(entry, tokenScores.sum() + phraseBonus, index)
    }.sortedWith(compareBy<RankedEntry> { it.score }.thenBy { it.originalIndex })
        .map(RankedEntry::entry)
}

private data class RankedEntry(val entry: TranscriptEntry, val score: Int, val originalIndex: Int)

private fun fuzzyTokenScore(token: String, candidate: String): Int? {
    val directIndex = candidate.indexOf(token)
    if (directIndex >= 0) return directIndex
    if (token.length < 3) return null

    val words = candidate.split(' ').filter(String::isNotBlank)
    val maxDistance = when {
        token.length >= 8 -> 2
        token.length >= 4 -> 1
        else -> 0
    }
    val typoScore = words.minOfOrNull { word ->
        val distance = editDistance(token, word, maxDistance)
        if (distance <= maxDistance) distance * 24 + kotlin.math.abs(token.length - word.length) else Int.MAX_VALUE
    } ?: Int.MAX_VALUE
    if (typoScore != Int.MAX_VALUE) return 200 + typoScore

    val subsequenceGap = subsequenceGap(token, candidate) ?: return null
    return if (subsequenceGap <= token.length * 3) 500 + subsequenceGap else null
}

private fun String.normalizedSearchText(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private fun subsequenceGap(needle: String, haystack: String): Int? {
    var needleIndex = 0
    var previousMatch = -1
    var gap = 0
    for (index in haystack.indices) {
        if (haystack[index] != needle[needleIndex]) continue
        if (previousMatch >= 0) gap += index - previousMatch - 1
        previousMatch = index
        needleIndex++
        if (needleIndex == needle.length) return gap
    }
    return null
}

private fun editDistance(left: String, right: String, cutoff: Int): Int {
    if (kotlin.math.abs(left.length - right.length) > cutoff) return cutoff + 1
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (leftIndex in left.indices) {
        current[0] = leftIndex + 1
        var rowMinimum = current[0]
        for (rightIndex in right.indices) {
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1,
            )
            rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
        }
        if (rowMinimum > cutoff) return cutoff + 1
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}
