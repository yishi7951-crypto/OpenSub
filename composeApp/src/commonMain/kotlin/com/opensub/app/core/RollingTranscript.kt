package com.opensub.app.core

/** Time after a word in which the model still revises it: it needs to hear what follows. */
const val DEFAULT_SETTLE_SECONDS = 0.35f
private const val MOST_WAIT_SECONDS = 3f
/**
 * The window grows with the speech up to this, then starts again this far behind the last word settled. A reading takes
 * about 35 ms per second of audio, so in a fast two-person talk 20 and 8 seconds made readings of 0.35 to 0.56 s, longer than
 * the 0.25 s between them, and words came up 1.6 s after they were said. 12 and 4 read as accurately (Churchill 4.4% to
 * 3.8% of words wrong, Kennedy 2.6% to 2.6%).
 */
private const val LONGEST_WINDOW_SECONDS = 12f
private const val LEFT_CONTEXT_SECONDS = 4f
/** Parakeet reports word starts in steps of this size. */
private const val TIMING_STEP_SECONDS = 0.08f
private const val MOST_ALIGNED_WORDS = 6
private const val MIN_CUT_LETTERS = 3
private const val NEAR_SECONDS = 0.4f
private const val FAR_SECONDS = 1f
private const val SENTENCE_END = ".?!"

/**
 * Turns repeated readings of one moving audio window into text that only ever grows.
 *
 * A model reading a clip guesses at both edges: it cannot hear what comes next, and it always ends with a full stop.
 * Cutting speech into pieces puts those edges in the middle of sentences. Here every word is read many times inside a
 * window instead, and is committed once two readings agree and [settleSeconds] of audio follow it, so it was heard
 * with both sides and punctuation at that point is real. Words on screen are never rewritten; the left of the window
 * only gives the model context, and is matched against what has been committed so nothing is said twice.
 */
class RollingTranscript(private val settleSeconds: Float = DEFAULT_SETTLE_SECONDS, private val longestWindowSeconds: Float = LONGEST_WINDOW_SECONDS, private val leftContextSeconds: Float = LEFT_CONTEXT_SECONDS) {
    /**
     * [revisedLast] is the last word given earlier, with the punctuation a later reading heard across the pause.
     * [nextStartSeconds] is where a word heard after [words] starts: not settled yet, but it shows speech went on.
     * [nextText] is that word as heard so far. [pending] is everything heard after [words] that has not settled yet.
     */
    data class Update(val words: List<HeardWord>, val revisedLast: String? = null, val nextStartSeconds: Float? = null, val nextText: String? = null,
        val pending: List<HeardWord> = emptyList())

    var windowStartSeconds = 0f
        private set
    private val committed = ArrayDeque<HeardWord>()
    private var heardUntil = 0f
    private var previous = emptyList<HeardWord>()
    private var heldEnding = ""
    private var revised: String? = null

    fun read(heard: List<HeardWord>, nowSeconds: Float): Update {
        val fresh = unseen(heard)
        val agreed = fresh.indices.takeWhile { it < previous.size && key(fresh[it].text) == key(previous[it].text) }.size
        val settled = fresh.take(agreed).takeWhile { it.startSeconds < nowSeconds - settleSeconds }.size
        // A word the readings keep changing must still reach the screen before the speech has moved on.
        val overdue = fresh.drop(settled).takeWhile { it.startSeconds < nowSeconds - MOST_WAIT_SECONDS }.size
        val ready = fresh.take(settled) + fresh.drop(settled).take(overdue).map { it.copy(unsure = true) }
        commit(ready)
        previous = fresh.drop(ready.size)
        if (nowSeconds - windowStartSeconds > longestWindowSeconds) {
            windowStartSeconds = maxOf(windowStartSeconds, heardUntil - leftContextSeconds, nowSeconds - longestWindowSeconds)
        }
        return Update(ready, revised.also { revised = null }, previous.firstOrNull()?.startSeconds, previous.firstOrNull()?.text, previous)
    }

    /**
     * Speech has paused, so nothing follows and every remaining word must be shown. The window stays: if speech resumes,
     * the next reading hears across the pause. Until then a full stop here only means the audio ended, so it is held back.
     */
    fun pause(heard: List<HeardWord>): Update {
        val words = unseen(heard)
        val last = words.lastOrNull()
        val spoken = last?.let { it.copy(text = it.text.trimEnd { mark -> mark in SENTENCE_END }) }
        heldEnding = last?.text?.drop(spoken!!.text.length) ?: heldEnding
        val shown = if (spoken == null) words else words.dropLast(1) + spoken
        commit(shown)
        previous = emptyList()
        return Update(shown, revised.also { revised = null })
    }

    /** Long silence: whatever was said is complete, and later speech owes nothing to it. */
    fun forget(nowSeconds: Float, keepSeconds: Float): Update {
        val last = committed.lastOrNull()
        val ending = last?.text?.plus(heldEnding)?.takeIf { heldEnding.isNotEmpty() }
        committed.clear()
        previous = emptyList()
        heldEnding = ""
        windowStartSeconds = maxOf(windowStartSeconds, nowSeconds - keepSeconds)
        return Update(emptyList(), ending)
    }

    /** The left of the window is read again, so find where the committed words end: by the words, checked against time. */
    private fun unseen(heard: List<HeardWord>): List<HeardWord> {
        val last = committed.lastOrNull() ?: return heard
        for (count in minOf(MOST_ALIGNED_WORDS, committed.size) downTo 1) {
            val tail = committed.toList().takeLast(count).map { key(it.text) }
            if (tail.last().isEmpty()) continue
            for (end in heard.size downTo count) {
                // The same word can appear twice in a window, and a wrong last word can match an older one, so time decides.
                val near = kotlin.math.abs(heard[end - 1].startSeconds - last.startSeconds) <= if (count >= 3) FAR_SECONDS else NEAR_SECONDS
                if (!near) continue
                val seen = heard.subList(end - count, end).map { key(it.text) }
                if (seen.dropLast(1) != tail.dropLast(1) || !sameOrCut(seen.last(), tail.last())) continue
                revisePunctuation(heard[end - 1].text)
                return heard.drop(end)
            }
        }
        return heard.filter { it.startSeconds >= heardUntil - TIMING_STEP_SECONDS / 2 }
    }

    /** A word cut off at the edge of a reading is still that word, but "a" is not the start of "ago". */
    private fun sameOrCut(first: String, second: String): Boolean {
        val shorter = minOf(first.length, second.length)
        return first == second || (shorter >= MIN_CUT_LETTERS && (first.startsWith(second) || second.startsWith(first)))
    }

    private fun revisePunctuation(heard: String) {
        val last = committed.last()
        val text = last.text.trimEnd { !it.isLetterOrDigit() } + heard.dropWhile { !it.isLetterOrDigit() }.dropWhile { it.isLetterOrDigit() || it == '\'' }
        heldEnding = ""
        if (text != last.text) { committed[committed.lastIndex] = last.copy(text = text); revised = text }
    }

    private fun commit(words: List<HeardWord>) {
        committed += words
        while (committed.size > MOST_ALIGNED_WORDS) committed.removeFirst()
        words.lastOrNull()?.let { heardUntil = it.startSeconds + TIMING_STEP_SECONDS }
    }

    private fun key(word: String) = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
}
