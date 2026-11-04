package com.opensub.app.core

import kotlin.math.sqrt

private const val WINDOW = 512
private const val MIN_GAP_WINDOWS = 2
private const val GAP_LEVEL = 0.35f

/**
 * Where to cut speech that never pauses, so that no word is split: the middle of the longest quiet dip at or after
 * [earliest], the latest one on a tie. Speech with no dip at all, such as speech over music, is cut at its quietest moment.
 * The method follows LiveSub's OfflinePhraseRecognizer (see THIRD_PARTY.md).
 */
fun findWordGap(samples: FloatArray, earliest: Int): Int {
    val levels = FloatArray(samples.size / WINDOW) { level(samples, it * WINDOW) }
    val first = earliest / WINDOW
    if (first >= levels.size) return samples.size
    val quiet = levels.sorted()[levels.size / 2] * GAP_LEVEL
    var run = 0
    var longest = 0
    var middle = -1
    for (index in first until levels.size) {
        run = if (levels[index] < quiet) run + 1 else 0
        if (run >= MIN_GAP_WINDOWS && run >= longest) {
            longest = run
            middle = (index + 1) * WINDOW - run * WINDOW / 2
        }
    }
    if (middle >= 0) return middle
    return (first until levels.size).minBy { levels[it] } * WINDOW + WINDOW / 2
}

private fun level(samples: FloatArray, start: Int): Float {
    var sum = 0f
    for (index in start until start + WINDOW) sum += samples[index] * samples[index]
    return sqrt(sum / WINDOW)
}
