package com.opensub.app.core

private const val MAX_WORDS = 30
private const val MAX_GAP_MS = 1500L
private const val SENTENCE_END = ".?!"

/** Replaces the parts a translated sentence covers. Later parts, not yet folded in, stay. */
fun foldTranslation(cues: List<SubtitleCue>, translated: SubtitleCue): List<SubtitleCue> =
    (cues.filter { it.id < translated.id || it.startMs >= translated.endMs } + translated).sortedBy { it.id }

/**
 * Pauses split speech mid-sentence, and a translator given half a sentence guesses badly. The recognizer's own
 * punctuation shows what is unfinished: a part without a full stop, or the next part starting in lower case.
 * Each part is folded into a growing cue, so Chinese can appear early and improve as the sentence completes.
 */
class SentenceGrouper {
    private var current: SubtitleCue? = null

    fun add(cue: SubtitleCue): SubtitleCue = peek(cue).also { current = it }

    /** Takes [cue] as the sentence in progress, when a closer look has settled what it is. */
    fun replace(cue: SubtitleCue) { current = cue }

    /** What the sentence would be with [cue] added, for a draft that may still change. */
    fun peek(cue: SubtitleCue): SubtitleCue {
        val previous = current
        return if (previous != null && continues(previous, cue)) join(previous, cue) else cue
    }

    private fun continues(previous: SubtitleCue, next: SubtitleCue): Boolean {
        val words = previous.english.split(' ').size + next.english.split(' ').size
        if (next.startMs - previous.endMs >= MAX_GAP_MS || words > MAX_WORDS) return false
        return previous.english.trimEnd().lastOrNull()?.let { it in SENTENCE_END } != true || next.english.firstOrNull()?.isLowerCase() == true
    }

    private fun join(previous: SubtitleCue, next: SubtitleCue): SubtitleCue =
        previous.copy(endMs = next.endMs, english = joinSentence(previous.english, next.english), chinese = "")
}

/** Reads two pieces as one sentence. Capitals left over from the split read to the translator as a new sentence or a name. */
fun joinSentence(first: String, next: String): String {
    val second = if (next.substringBefore(' ').trimEnd(',') == "I") next else next.replaceFirstChar { it.lowercase() }
    return "${first.trimEnd { it in SENTENCE_END }} $second"
}
