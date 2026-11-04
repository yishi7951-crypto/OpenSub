package com.opensub.app.core

import kotlin.concurrent.Volatile

private const val MIN_LINE_WORDS = 5
private const val FIRST_LINE_WORDS = 3
private const val LINES_PER_PAGE = 2
private const val CHINESE_BREAKS = "，。！？；：,.!?;:"
private const val ALIGN_WORDS = 3
private const val ALIGN_REACH = 3

/**
 * Centred text that grows re-centres on every word, so everything already on screen slides sideways.
 * Each line keeps its place as more words arrive. A final result may correct its words in place without replaying
 * an older page. Two lines make a page, and a full page gives way to a new one.
 */
class CaptionPager(var maxLineChars: Int = 42) {
    private var sentence = -1L
    private var placed = emptyList<String>()
    private data class Line(val sentence: Long, var from: Int, var until: Int, var text: String)
    private val lines = ArrayList<Line>()
    private var page = 0L
    private var opening = true
    @Volatile private var clearRequested = false
    @Volatile private var resetRequested = false

    /** Safe from a timer thread: the page is dropped before the next caption is laid out. */
    fun clear() { clearRequested = true }
    fun reset() { resetRequested = true; clearRequested = true }

    /** Returns [cue] rewritten as the current page, or null when nothing on screen should change. */
    fun accept(cue: SubtitleCue): SubtitleCue? {
        if (clearRequested) {
            clearRequested = false; newPage()
            if (resetRequested) { resetRequested = false; sentence = -1; placed = emptyList(); opening = true }
        }
        if (cue.id != sentence) { sentence = cue.id; placed = emptyList() }
        val words = cue.english.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val consumed = afterPlaced(words)
        var changed = if (cue.isFinal && placed.isNotEmpty()) correct(words.take(consumed)) else false
        var rest = words.drop(consumed)
        while (rest.isNotEmpty()) {
            val line = takeLine(rest)
            // A line the width has already filled is ready, however few words it holds.
            val minimum = if (opening) FIRST_LINE_WORDS else MIN_LINE_WORDS
            if (!cue.isFinal && line.size < minimum && line.size == rest.size) break
            if (lines.size >= LINES_PER_PAGE) newPage()
            lines += Line(sentence, placed.size, placed.size + line.size, line.joinToString(" "))
            opening = false
            placed = placed + line
            rest = rest.drop(line.size)
            changed = true
        }
        return if (changed) cue.copy(id = page, english = lines.joinToString("\n") { it.text }) else null
    }

    /** Align word boundaries so a correction such as "can not" -> "cannot" cannot repeat or swallow the next word. */
    private fun correct(prefix: List<String>): Boolean {
        if (prefix == placed) return false
        val costs = Array(placed.size + 1) { IntArray(prefix.size + 1) }
        for (i in 0..placed.size) costs[i][0] = i
        for (j in 0..prefix.size) costs[0][j] = j
        for (i in 1..placed.size) for (j in 1..prefix.size) {
            val edit = if (comparable(placed[i - 1]) == comparable(prefix[j - 1])) 0 else 1
            costs[i][j] = minOf(costs[i - 1][j - 1] + edit, costs[i - 1][j] + 1, costs[i][j - 1] + 1)
        }
        val boundaries = IntArray(placed.size + 1)
        var i = placed.size
        var j = prefix.size
        boundaries[i] = j
        while (i > 0) {
            val edit = if (j > 0 && comparable(placed[i - 1]) == comparable(prefix[j - 1])) 0 else 1
            when {
                j > 0 && costs[i][j] == costs[i - 1][j - 1] + edit -> { i--; j--; boundaries[i] = j }
                costs[i][j] == costs[i - 1][j] + 1 -> { i--; boundaries[i] = j }
                else -> j--
            }
        }
        boundaries[0] = 0
        var changed = false
        for (line in lines.filter { it.sentence == sentence }) {
            val from = boundaries[line.from]
            val until = boundaries[line.until]
            val text = prefix.subList(from, until).joinToString(" ")
            if (line.text != text) changed = true
            line.from = from; line.until = until; line.text = text
        }
        lines.removeAll { it.text.isEmpty() }
        placed = prefix
        return changed
    }

    /**
     * The newest shown words locate the join, because a revision can change how many words came before it.
     */
    private fun afterPlaced(words: List<String>): Int {
        if (placed.isEmpty()) return 0
        val tail = placed.takeLast(ALIGN_WORDS).map(::comparable)
        val candidates = (placed.size - ALIGN_REACH..placed.size + ALIGN_REACH).filter { it in tail.size..words.size }
        val best = candidates.maxByOrNull { end ->
            val matches = tail.indices.count { comparable(words[end - tail.size + it]) == tail[it] }
            matches * 2 * ALIGN_REACH - kotlin.math.abs(end - placed.size)
        }
        val aligned = best?.takeIf { end -> tail.indices.any { comparable(words[end - tail.size + it]) == tail[it] } }
        return aligned ?: minOf(placed.size, words.size)
    }

    private fun newPage() { lines.clear(); page++ }

    private fun takeLine(words: List<String>): List<String> {
        var length = words.first().length
        var count = 1
        while (count < words.size && length + 1 + words[count].length <= maxLineChars) length += 1 + words[count++].length
        return words.take(count)
    }

    private fun comparable(word: String) = word.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * Shows a growing sentence one page at a time, breaking after punctuation where possible, so the card never grows.
 * Pages are counted from the start of the text: words on screen stay put as more arrive, until a whole new page replaces them.
 */
fun fitChinese(text: String, maxChars: Int): String {
    var start = 0
    while (text.length - start > maxChars) {
        val end = start + maxChars
        start = (end downTo start + maxChars / 2 + 1).firstOrNull { text[it - 1] in CHINESE_BREAKS } ?: end
    }
    return text.substring(start)
}
