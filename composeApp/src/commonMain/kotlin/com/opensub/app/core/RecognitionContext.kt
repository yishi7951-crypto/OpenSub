package com.opensub.app.core

/** Keeps the original sample clock: adjacent pieces touch, real pauses stay silent, overlaps occur only once. */
fun joinAudio(parts: List<Utterance>): Utterance {
    require(parts.isNotEmpty())
    val start = parts.first().startSample
    val end = parts.maxOf { it.startSample + it.samples.size }
    val samples = FloatArray((end - start).toInt())
    for (part in parts) part.samples.copyInto(samples, (part.startSample - start).toInt())
    return Utterance(samples, start, parts.last().cutMidSpeech, parts.last().trailingSamples)
}

data class RecognitionInput(val audio: Utterance, val newAudioSeconds: Float = 0f, val previousText: String = "") {
    /** Left context helps recognition but must never be emitted a second time. */
    fun newWords(words: List<HeardWord>): List<HeardWord> {
        val owned = if (audio.trailingSamples.isEmpty()) words else words.takeWhile { it.startSeconds < audio.samples.size.toFloat() / SAMPLE_RATE }
        val previous = previousText.split(Regex("\\s+")).map(::comparable)
        // Prefer the words already emitted to a coarse timestamp at the cut. Repeated words AFTER the cut stay.
        for (count in minOf(6, previous.size) downTo 2) {
            val end = (count..owned.size).lastOrNull { end ->
                owned[end - 1].startSeconds < newAudioSeconds + 0.08f &&
                    owned.subList(end - count, end).map { comparable(it.text) } == previous.takeLast(count)
            }
            if (end != null) return owned.drop(end)
        }
        // TDT has an 80 ms step; the first word can be assigned to audio just before the VAD/cut boundary.
        val next = owned.filter { it.startSeconds >= newAudioSeconds - 0.32f }
        val uncertain = next.takeWhile { it.startSeconds < newAudioSeconds + 0.08f }.size
        val overlap = (minOf(uncertain, previous.size) downTo 1).firstOrNull { count ->
            previous.takeLast(count) == next.take(count).map { comparable(it.text) }
        } ?: 0
        return next.drop(overlap)
    }

    fun text(words: List<HeardWord>): String = newWords(words).joinToString(" ") { it.text }

    private fun comparable(word: String) = word.lowercase().filter { it.isLetterOrDigit() }
}

/** A short left context survives across previews and final pieces. Long pauses start afresh. */
class RecognitionContext {
    private var current: Utterance? = null
    private var previous: Utterance? = null
    private var currentText = ""
    private var previousText = ""

    fun remember(text: String) { currentText = text }

    fun input(utterance: Utterance): RecognitionInput {
        val last = current
        if (last != null && utterance.startSample != last.startSample) {
            previous = last.takeIf { utterance.startSample > it.startSample }
            previousText = if (previous == null) "" else currentText
            currentText = ""
        }
        current = utterance
        val left = previous ?: return RecognitionInput(utterance)
        val end = minOf(left.startSample + left.samples.size, utterance.startSample)
        val gap = utterance.startSample - end
        if (gap >= SAMPLE_RATE || end <= left.startSample) return RecognitionInput(utterance)
        val start = maxOf(left.startSample, end - 3 * SAMPLE_RATE)
        val tail = Utterance(left.samples.copyOfRange((start - left.startSample).toInt(), (end - left.startSample).toInt()), start)
        return RecognitionInput(joinAudio(listOf(tail, utterance)), (utterance.startSample - start).toFloat() / SAMPLE_RATE, previousText)
    }
}
