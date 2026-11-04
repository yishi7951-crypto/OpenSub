package com.opensub.app.core

/** A sentence's newest Chinese stays up this long before the next sentence takes the window... */
private const val READ_MS = 1000L
/** ...unless that sentence has already waited this long: its English is what keeps up with the voice. */
private const val LONGEST_WAIT_MS = 1000L

/**
 * The subtitle window shows one sentence at a time, its English as it is heard with its own Chinese beneath, the way the
 * history list pairs them. The user asked for this after the rolling rows (several sentences on four rows, the Chinese a
 * phrase or two behind the English) piled up on screen while the list looked right.
 *
 * The next sentence takes the window once this one's Chinese is all in and its newest piece has been up for [READ_MS], or
 * once the next sentence has waited [LONGEST_WAIT_MS]. Dialogue rarely waits; a talk, where the next sentence starts as
 * the Chinese of the last one arrives, waited about 0.7 s a sentence in a replay, and without the wait three pieces of
 * Chinese were gone before they showed. Chinese that comes after its sentence has left is only in the history.
 *
 * A sentence longer than two rows rolls within itself ([RollUp]). Times are the caller's clock in milliseconds.
 */
class SentenceCaption(englishRowChars: () -> Int, chineseRowChars: () -> Int, private val readsChinese: () -> Boolean = { true }) {
    data class Shown(val sentence: Long = -1, val english: String = "", val chinese: String = "")

    private class Sentence(val heardMs: Long) {
        var english = ""
        val chinese = ArrayList<Pair<Long, String>>()
        var finished = false
    }

    private val englishRows = RollUp(englishRowChars, " ")
    private val chineseRows = RollUp(chineseRowChars, "　")
    private val sentences = LinkedHashMap<Long, Sentence>()
    private var shownSinceMs = 0L
    private var chineseAtMs = 0L
    var shown = Shown()
        private set

    fun english(sentence: Long, text: String, nowMs: Long): Shown {
        if (sentence < shown.sentence) return shown
        sentences.getOrPut(sentence) { Sentence(nowMs) }.english = text
        if (sentence == shown.sentence) shown = shown.copy(english = englishRows.put(sentence, text))
        return advance(nowMs)
    }

    fun chinese(sentence: Long, unit: Long, text: String, nowMs: Long): Shown {
        if (sentence < shown.sentence) return shown
        sentences.getOrPut(sentence) { Sentence(nowMs) }.chinese += unit to text
        if (sentence == shown.sentence) {
            shown = shown.copy(chinese = chineseRows.put(unit, text))
            chineseAtMs = nowMs
        }
        return advance(nowMs)
    }

    /** Every piece of this sentence's Chinese has come, or it has none. */
    fun finished(sentence: Long, nowMs: Long): Shown {
        sentences[sentence]?.finished = true
        return advance(nowMs)
    }

    /** Lets the next sentence in when its turn has come. */
    fun advance(nowMs: Long): Shown {
        val next = sentences.keys.firstOrNull { it > shown.sentence } ?: return shown
        if (shown.sentence < 0 || leaves(next, nowMs)) show(next, nowMs)
        return shown
    }

    /** How long until [advance] may let the next sentence in, or null when none is waiting. */
    fun waitMs(nowMs: Long): Long? {
        val next = sentences.entries.firstOrNull { it.key > shown.sentence }?.value ?: return null
        val current = sentences[shown.sentence] ?: return 0
        var at = maxOf(next.heardMs, shownSinceMs) + LONGEST_WAIT_MS
        if (current.finished) at = minOf(at, if (current.chinese.isEmpty()) nowMs else chineseAtMs + READ_MS)
        return (at - nowMs).coerceAtLeast(0)
    }

    fun clear() {
        sentences.clear()
        englishRows.clear()
        chineseRows.clear()
        shown = Shown()
    }

    private fun leaves(next: Long, nowMs: Long): Boolean {
        val current = sentences[shown.sentence] ?: return true
        if (!readsChinese()) return true
        if (nowMs - maxOf(sentences.getValue(next).heardMs, shownSinceMs) >= LONGEST_WAIT_MS) return true
        return current.finished && (current.chinese.isEmpty() || nowMs - chineseAtMs >= READ_MS)
    }

    private fun show(id: Long, nowMs: Long) {
        sentences.keys.removeAll { it < id }
        val sentence = sentences.getValue(id)
        englishRows.clear()
        chineseRows.clear()
        var chinese = ""
        for ((unit, text) in sentence.chinese) chinese = chineseRows.put(unit, text)
        shown = Shown(id, englishRows.put(id, sentence.english), chinese)
        shownSinceMs = nowMs
        chineseAtMs = nowMs
    }
}
