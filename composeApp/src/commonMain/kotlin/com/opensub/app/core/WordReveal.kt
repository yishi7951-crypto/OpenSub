package com.opensub.app.core

data class RevealedWord(val start: Int, val end: Int, val opacity: Float)
data class WordRevealFrame(val text: String = "", val words: List<RevealedWord> = emptyList(), val animating: Boolean = false) {
    val visibleText: String get() = text.take(words.lastOrNull()?.end ?: 0)
}

/** Retargets the current recognition result. Animation never queues old text behind new speech. */
class WordReveal {
    private var id: Long? = null
    private var text = ""
    private var ranges = emptyList<IntRange>()
    private var revealedAt = LongArray(0)
    private var visible = 0
    private var nextAt = 0L
    private var gapMs = 0L

    fun target(cueId: Long?, value: String, nowMs: Long) {
        if (cueId == id && value == text) return
        advance(nowMs)
        val sameSentence = cueId != null && cueId == id
        val previousTimes = revealedAt
        id = cueId
        text = if (cueId == null) "" else value
        ranges = Regex("\\S+").findAll(text).map { it.range }.toList()
        visible = if (sameSentence) minOf(visible, ranges.size) else 0
        revealedAt = LongArray(ranges.size) { index -> if (index < visible) previousTimes[index] else Long.MAX_VALUE }
        val pending = ranges.size - visible
        // One word appears immediately; even a large new batch catches up within 220 ms.
        gapMs = if (pending == 0) 0 else minOf(70L, 220L / pending)
        nextAt = nowMs
    }

    fun frame(nowMs: Long): WordRevealFrame {
        advance(nowMs)
        val words = (0 until visible).map { index ->
            RevealedWord(ranges[index].first, ranges[index].last + 1, ((nowMs - revealedAt[index]) / 80f).coerceIn(0f, 1f))
        }
        return WordRevealFrame(text, words, visible < ranges.size || words.any { it.opacity < 1f })
    }

    private fun advance(nowMs: Long) {
        while (visible < ranges.size && nowMs >= nextAt) {
            revealedAt[visible++] = nextAt
            nextAt += gapMs
        }
    }
}
