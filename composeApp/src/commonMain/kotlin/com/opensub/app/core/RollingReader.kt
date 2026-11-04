package com.opensub.app.core

/** Long enough that a breath between sentences keeps its context, short enough that a sentence ending in silence closes soon. */
private const val FORGET_AFTER_SECONDS = 1.5f
/** Silence kept before speech, because the detector notices speech a moment after it starts. */
private const val KEEP_BEFORE_SPEECH_SECONDS = 1f
/** Speech before a sentence that its second reading hears. More cost time and gained nothing. */
private const val SENTENCE_CONTEXT_SECONDS = 8f
/** Word starts move a little between readings, so a word on the edge of a sentence is judged a little early. */
private const val EDGE_SECONDS = 0.12f
/** No new word for this long is a pause, even when room noise keeps the speech detector on. */
private const val WORDS_STOPPED_SECONDS = 1f
/** Long enough for the last word to have ended. A wrong guess only splits a subtitle; a right one saves it half a second. */
private const val SUBTITLE_STOPPED_SECONDS = 0.6f
/** No new word for this long is a stop even without a full stop: a film scene's sound keeps the detector on, and in real use "Where I am" waited ten seconds. */
private const val WORDS_SILENT_SECONDS = 1.5f
/** No new word for this long ends the sentence: what is said after that is a new one, not more of it. */
private const val GIVE_UP_SECONDS = 4f
/** A reading this far from the first one has gone wrong at an edge, and the first one is kept. */
private const val MOST_CHANGED_SHARE = 0.3
/** Right after a seek little or no audio can be read yet, and the recognizer fails on too little rather than hearing nothing. */
private const val FEWEST_SAMPLES = SAMPLE_RATE / 10

/**
 * One reading of the rolling window at a time: asks the model, then turns what it heard into cues.
 * Kept apart from [RollingCaptionPipeline] so a recording can be scored through the same steps without the real-time clock.
 *
 * Words reach the screen after a short look at the audio around them. Once a sentence closes it is read once more,
 * whole, with the speech before it: that reading is about a quarter more accurate, and costs one reading per sentence.
 * It goes to the final translation, the export and the current line.
 */
class RollingReader(
    private val recognizer: SpeechRecognizer,
    private val transcript: RollingTranscript = RollingTranscript(),
) {
    /**
     * [atPause]: the speaker stopped, and this is the open sentence read whole. Sentences usually end at a pause but only
     * close once speech resumes, seconds later, so the translation is worth making now.
     * [live]: the open sentence has grown by a phrase and should go on screen. Its [SubtitleCue.sentenceEnglish] is the part
     * steady enough to translate.
     * [stoppedAt]: the speaker has stopped, so all of the open sentence can be shown, however short. A sentence closes only when
     * the next word comes, and a subtitle should not wait seconds for that. Its [SubtitleCue.english] is the second reading
     * when there is one, and its [SubtitleCue.sentenceEnglish] the words as first heard, which subtitles count their way through.
     * [guess]: the open sentence with the words heard after it that have not settled yet. Worth translating ahead when they
     * complete a subtitle: the words usually settle as they are.
     */
    class Step(val update: RollingTranscript.Update, val cues: List<SubtitleCue>, val paused: Boolean, val atPause: SubtitleCue? = null,
        val live: SubtitleCue? = null, val stoppedAt: SubtitleCue? = null, val guess: SubtitleCue? = null)

    private val sentences = SpokenSentences()
    private var wasSpeaking = false
    private var quietSince = 0f
    private var latest: Utterance? = null
    private var lastWordSeconds = 0f
    private var readAtPause: SubtitleCue? = null
    private var wordsAtPause = 0
    private var lastClosedWord: String? = null
    private var heardFromSeconds = 0f
    private var guessed = ""

    /** The audio [step] needs from here on: the rolling window, and all of the open sentence with the speech before it. */
    val keepFromSeconds: Float
        get() = minOf(transcript.windowStartSeconds, (sentences.openStartSeconds ?: Float.MAX_VALUE) - SENTENCE_CONTEXT_SECONDS).coerceAtLeast(heardFromSeconds)

    /** [clip] may be null when nothing is being said. Returns null when this reading changes nothing. */
    suspend fun step(clip: Utterance?, speaking: Boolean, endSample: Long): Step? {
        clip?.let { latest = it }
        val now = endSample.toFloat() / SAMPLE_RATE
        val paused = wasSpeaking && !speaking
        val update = when {
            speaking -> transcript.read(rollingReading(clip), now)
            paused -> transcript.pause(rollingReading(clip))
            now - quietSince > FORGET_AFTER_SECONDS -> transcript.forget(now, KEEP_BEFORE_SPEECH_SECONDS)
            else -> null
        }
        if (speaking || paused) quietSince = now
        wasSpeaking = speaking
        if (update == null) return null
        val heardNothing = update.words.isEmpty() && update.nextStartSeconds == null
        val cues = sentences.take(update)
        // After long silence the sentence is as complete as it will ever be, and so it is after long sound with no words in it.
        val longQuiet = (!speaking && !paused && now - quietSince > FORGET_AFTER_SECONDS) || (heardNothing && now - lastWordSeconds > GIVE_UP_SECONDS)
        val closing = if (longQuiet) cues + sentences.finish() else cues
        update.words.lastOrNull()?.let { lastWordSeconds = it.startSeconds }
        val silentFor = if (update.nextStartSeconds == null) now - lastWordSeconds else 0f
        val stopped = paused || silentFor >= WORDS_SILENT_SECONDS || (silentFor >= WORDS_STOPPED_SECONDS && sentences.endsWithStop)
        val live = sentences.ready()
        val subtitleStopped = stopped || (silentFor >= SUBTITLE_STOPPED_SECONDS && sentences.endsWithStop)
        // Read again before translating: the first hearing had "I don't pull the trigger" where the speaker said "I didn't".
        val atPause = if (subtitleStopped) readOpenSentence() else null
        return Step(update, readAgain(closing, update.nextStartSeconds), paused, atPause, live, atPause, if (subtitleStopped) null else guess(update))
    }

    /**
     * Once per text: the open sentence and the unsettled words after it, unless those start the next sentence. Guessing at
     * every change costs English nothing measurable: a second run without guesses was as slow as the runs with them, because
     * the computer's speed varied more between runs (readings 150 to 210 ms) than guessing ever changed it.
     */
    private fun guess(update: RollingTranscript.Update): SubtitleCue? {
        if (update.pending.isEmpty()) return null
        val open = sentences.open() ?: return null
        if (sentences.endsWithStop) return null
        val text = open.english + " " + update.pending.joinToString(" ") { it.text }
        if (text == guessed) return null
        guessed = text
        return open.copy(english = text, sentenceEnglish = text)
    }

    /**
     * Playback stopped at [endSample]: what was heard ends here, whole or not, and nothing after this point is read together
     * with anything before it, because it may come from anywhere in the video.
     */
    suspend fun interrupt(clip: Utterance?, endSample: Long): Step {
        clip?.let { latest = it }
        val now = endSample.toFloat() / SAMPLE_RATE
        val last = transcript.pause(rollingReading(clip))
        val closed = readAgain(sentences.take(last) + sentences.finish()).map { it.copy(cut = true) }
        transcript.forget(now, 0f)
        heardFromSeconds = now
        wasSpeaking = false
        quietSince = now
        readAtPause = null
        lastClosedWord = null
        return Step(last, closed, true)
    }

    suspend fun finish(): Step {
        val last = transcript.forget(0f, 0f)
        return Step(last, readAgain(sentences.take(last) + sentences.finish()), false)
    }

    private suspend fun rollingReading(clip: Utterance?) = clip?.let { heard(it.from(transcript.windowStartSeconds)) } ?: emptyList()

    /** Once per pause: nothing has changed until another word arrives, so the reading made then stands. */
    private suspend fun readOpenSentence(): SubtitleCue? {
        val open = sentences.open() ?: return null
        readAtPause?.takeIf { it.id == open.id && wordCount(open) == wordsAtPause }?.let { return it }
        readAtPause = null
        // Units count their way through the words as heard, which a second reading can change in number.
        val whole = wholeSentence(open, null, null).copy(sentenceEnglish = open.english)
        wordsAtPause = wordCount(open)
        readAtPause = whole
        return whole
    }

    private fun wordCount(cue: SubtitleCue) = cue.english.split(' ').size

    /** A sentence closes because the next word came: either the cue after it starts there, or that word is still unsettled at [heardNext]. */
    private suspend fun readAgain(cues: List<SubtitleCue>, heardNext: Float? = null) = cues.mapIndexed { index, cue ->
        val next = cues.getOrNull(index + 1)
        if (!cue.isFinal) return@mapIndexed cue
        wholeSentence(cue, next?.startMs?.let { it / 1000f } ?: heardNext, next?.english?.substringBefore(' ')).also { lastClosedWord = it.english.substringAfterLast(' ') }.copy(sentenceEnglish = cue.english)
    }

    private suspend fun wholeSentence(cue: SubtitleCue, nextStart: Float?, nextWord: String?): SubtitleCue {
        // Already read at the pause, and the Chinese came from that text: a new reading would only change it for nothing.
        // The model ends every reading with a full stop, so the mark comes from how the sentence really closed.
        readAtPause?.takeIf { it.id == cue.id && wordCount(cue) == wordsAtPause }?.let { read ->
            val mark = cue.english.last().takeUnless { it.isLetterOrDigit() }?.toString().orEmpty()
            return cue.copy(english = read.english.trimEnd('.', '?', '!', ',', ';', ':') + mark)
        }
        val audio = latest ?: return cue
        val start = cue.startMs / 1000f
        if (audio.startMs > cue.startMs) return cue
        val end = nextStart ?: Float.MAX_VALUE
        val inside = heard(audio.from(maxOf(start - SENTENCE_CONTEXT_SECONDS, heardFromSeconds))).filter { it.startSeconds >= start - EDGE_SECONDS && it.startSeconds < end - EDGE_SECONDS }
        // The next sentence's first word can still land just inside, and it is already on screen.
        val trimmedEnd = if (nextWord != null && plainWord(inside.lastOrNull()?.text) == plainWord(nextWord)) inside.dropLast(1) else inside
        // And the last sentence's final word can land just inside the start.
        val repeatsLast = plainWord(trimmedEnd.firstOrNull()?.text) == plainWord(lastClosedWord) && plainWord(cue.english.substringBefore(' ')) != plainWord(lastClosedWord)
        val words = if (repeatsLast) trimmedEnd.drop(1) else trimmedEnd
        val before = wordCount(cue)
        if (kotlin.math.abs(words.size - before) > before * MOST_CHANGED_SHARE) return cue
        val text = words.joinToString(" ") { it.text }
        // The end of a sentence is judged with the speech after it, which this reading stops short of.
        val ending = cue.english.last().takeIf { it in ".?!" && text.last() !in ".?!" }
        return cue.copy(english = if (ending == null) text else text.trimEnd(',', ';', ':') + ending)
    }

    private fun plainWord(word: String?) = word?.lowercase()?.filter { it.isLetterOrDigit() }

    private suspend fun heard(clip: Utterance): List<HeardWord> {
        if (clip.samples.size < FEWEST_SAMPLES) return emptyList()
        val offset = clip.startSample.toFloat() / SAMPLE_RATE
        return recognizer.words(clip).map { it.copy(startSeconds = it.startSeconds + offset) }
    }

    private fun Utterance.from(seconds: Float): Utterance {
        val first = ((seconds * SAMPLE_RATE).toLong() - startSample).coerceIn(0, samples.size.toLong()).toInt()
        return if (first == 0) this else Utterance(samples.copyOfRange(first, samples.size), startSample + first)
    }
}
