package com.opensub.app.core

private const val MAX_GAP_MS = 1500L
private const val MAX_WINDOW_SAMPLES = 14 * SAMPLE_RATE
private const val SENTENCE_END = ".?!"
private const val MAX_CARRIED_WORDS = 50

/**
 * The recognizer ends every piece with a full stop and capitalizes the next, because it cannot hear past the end of its
 * audio. So punctuation at a join between pieces means nothing. Recognizing the two sides together lets it hear across
 * the join: only then does a full stop there show a real sentence end. The same pass also corrects words cut off from
 * their context. Sentences that end inside a piece stay in the group; [SentenceTranslator] handles those.
 * A piece the model starts in lower case is the rest of a sentence, whatever the full stop before it says. When the
 * audio window is full, such a piece joins by text alone and the older audio drops out of the window.
 * Recognizers that report no timing fall back to the punctuation rules of [SentenceGrouper].
 */
class SentenceWindow(private val recognizer: SpeechRecognizer) {
    private val grouper = SentenceGrouper()
    private val parts = ArrayList<Utterance>()
    private var open: SubtitleCue? = null
    private var carried = ""
    private var carriedAtCut = false
    var latestEnglish: String = ""
        private set

    /** What the sentence would be with [cue] added, for a draft that may still change. */
    fun peek(cue: SubtitleCue): SubtitleCue = grouper.peek(cue)

    /** Returns what to translate now: the sentence [cue] joined, or the sentence it closed followed by [cue]. */
    suspend fun add(cue: SubtitleCue, utterance: Utterance): List<SubtitleCue> {
        latestEnglish = cue.english
        val previous = open
        val samples = previous?.let { utterance.startSample + utterance.samples.size - parts.first().startSample } ?: 0
        if (previous == null || cue.startMs - previous.endMs >= MAX_GAP_MS) return listOf(start(cue, utterance))
        if (samples > MAX_WINDOW_SAMPLES) return listOf(if (runsOn(previous, cue)) carry(previous, cue, utterance) else start(cue, utterance))
        val audio = joinAudio(parts + utterance)
        val heard = recognizer.words(audio).let { words ->
            if (audio.trailingSamples.isEmpty()) words else words.takeWhile { it.startSeconds < audio.samples.size.toFloat() / SAMPLE_RATE }
        }
        if (heard.isEmpty()) {
            val grouped = grouper.add(cue)
            if (grouped.id == cue.id) parts.clear()
            parts += utterance
            open = grouped
            return listOf(grouped)
        }
        val join = (utterance.startSample - parts.first().startSample).toFloat() / SAMPLE_RATE
        val after = RecognitionInput(audio, join, previous.english).newWords(heard)
        latestEnglish = utterance.sentenceText(after.text())
        val before = heard.take(heard.size - after.size)
        val sentenceEnded = before.lastOrNull()?.text?.lastOrNull()?.let { it in SENTENCE_END } == true && after.isNotEmpty()
        if (sentenceEnded && !cue.startsLowerCase()) return listOf(previous.copy(english = whole(before.text())),
            start(cue.copy(english = latestEnglish), utterance))
        val merged = previous.copy(endMs = cue.endMs, chinese = "",
            english = whole(utterance.sentenceText(if (sentenceEnded) joinSentence(before.text(), after.text()) else heard.text())))
        parts += utterance
        open = merged
        grouper.replace(merged)
        return listOf(merged)
    }

    private fun runsOn(previous: SubtitleCue, cue: SubtitleCue): Boolean {
        val words = previous.english.split(' ').size + cue.english.split(' ').size
        return words <= MAX_CARRIED_WORDS && (cue.startsLowerCase() || previous.english.trimEnd().lastOrNull()?.let { it in SENTENCE_END } != true)
    }

    private fun carry(previous: SubtitleCue, cue: SubtitleCue, utterance: Utterance): SubtitleCue {
        carriedAtCut = parts.last().cutMidSpeech
        val merged = previous.copy(endMs = cue.endMs, english = continuation(previous.english, cue.english), chinese = "")
        carried = previous.english
        parts.clear()
        parts += utterance
        open = merged
        grouper.replace(merged)
        return merged
    }

    /** Puts back the words whose audio has left the window. */
    private fun whole(text: String) = if (carried.isEmpty()) text else continuation(carried, text)

    /** A word crossing an acoustic cut may be heard on both sides of the rolling window. */
    private fun continuation(first: String, next: String): String {
        if (!carriedAtCut) return joinSentence(first, next)
        val before = first.trim().split(Regex("\\s+"))
        val after = next.trim().split(Regex("\\s+"))
        fun key(word: String) = word.lowercase().filter { it.isLetterOrDigit() }
        val overlap = (minOf(6, before.size, after.size) downTo 1).firstOrNull { count ->
            before.takeLast(count).map(::key) == after.take(count).map(::key)
        } ?: 0
        return if (overlap == after.size) first else joinSentence(first, after.drop(overlap).joinToString(" "))
    }

    private fun SubtitleCue.startsLowerCase() = english.firstOrNull()?.isLowerCase() == true

    private fun start(cue: SubtitleCue, utterance: Utterance): SubtitleCue {
        carried = ""
        carriedAtCut = false
        parts.clear()
        parts += utterance
        open = cue
        grouper.replace(cue)
        return cue
    }

    private fun List<HeardWord>.text() = joinToString(" ") { it.text }
}

/** Builds words from recognizer tokens, where a leading space or ▁ marks the start of a word. */
fun heardWords(tokens: List<String>, startSeconds: List<Float>): List<HeardWord> {
    if (tokens.size != startSeconds.size) return emptyList()
    val words = ArrayList<HeardWord>()
    for (index in tokens.indices) {
        val token = tokens[index]
        val text = token.trimStart(' ', '▁')
        if (words.isEmpty() || text.length < token.length) words += HeardWord(text, startSeconds[index])
        else words[words.lastIndex] = words.last().copy(text = words.last().text + text)
    }
    return words.filter { it.text.isNotEmpty() }
}
