package com.opensub.app.core

/**
 * The recognizer re-reads a growing clip, so the last words of each preview often change. Showing only the
 * words two readings agree on keeps text from flickering, at the cost of one preview interval. It never shrinks.
 */
class PreviewStabilizer {
    private var sentence: Long? = null
    private var previous = emptyList<String>()
    private var shown = 0
    private var settled = ""

    /** Returns text to display when more of the sentence has settled, otherwise null. */
    fun accept(id: Long, text: String): String? {
        if (id != sentence) { sentence = id; previous = emptyList(); shown = 0; settled = "" }
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val agreed = words.indices.takeWhile { it < previous.size && comparable(words[it]) == comparable(previous[it]) }.size
        previous = words
        if (agreed == 0 || agreed < shown) return null
        val next = words.take(agreed).joinToString(" ").trimEnd('.', '?', '!')
        if (next == settled) return null
        shown = agreed
        settled = next
        return next
    }

    private fun comparable(word: String) = word.lowercase().filter { it.isLetterOrDigit() }
}
