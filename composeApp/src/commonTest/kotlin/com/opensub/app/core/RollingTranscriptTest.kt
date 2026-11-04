package com.opensub.app.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RollingTranscriptTest {
    /** One word every half second from [from], as a reading of the window would report them. */
    private fun heard(text: String, from: Float = 0f) = text.split(' ').mapIndexed { index, word -> HeardWord(word, from + index * 0.5f) }
    private fun List<HeardWord>.text() = joinToString(" ") { it.text }

    @Test fun aWordWaitsForTwoReadingsAndAudioAfterIt() {
        val transcript = RollingTranscript(0.5f)
        assertTrue(transcript.read(heard("we shall fight"), 2f).words.isEmpty())
        assertEquals("we shall fight", transcript.read(heard("we shall fight on"), 2.5f).words.text())
    }

    @Test fun theNewestWordsStayUncommittedWhileTheModelCanStillChangeThem() {
        val transcript = RollingTranscript(0.5f)
        transcript.read(heard("we shall fight"), 0.9f)
        assertEquals("we", transcript.read(heard("we shall fight"), 0.9f).words.text())
    }

    @Test fun readingsThatDisagreeCommitNothing() {
        val transcript = RollingTranscript(0.5f)
        transcript.read(heard("we shell fight"), 3f)
        assertEquals("we", transcript.read(heard("we shall fight"), 3.4f).words.text())
    }

    @Test fun aWordTheReadingsKeepChangingIsShownBeforeTheSpeechMovesOn() {
        val transcript = RollingTranscript(0.5f)
        transcript.read(heard("one"), 3.4f)
        assertEquals("two three", transcript.read(heard("two three four", 0.5f), 4.4f).words.text())
    }

    @Test fun nothingIsCommittedTwiceWhenTheWindowRereadsIt() {
        val transcript = RollingTranscript(0.5f)
        transcript.read(heard("we shall fight on the beaches"), 3f)
        assertEquals("we shall fight on the", transcript.read(heard("we shall fight on the beaches"), 3f).words.text())
        assertEquals("beaches", transcript.read(heard("we shall fight on the beaches we"), 3.5f).words.text())
        assertTrue(transcript.read(heard("we shall fight on the beaches we"), 3.5f).words.isEmpty())
    }

    @Test fun aWrongLastWordDoesNotMatchAnOlderCopyAndRepeatEverythingAfterIt() {
        val transcript = RollingTranscript(0.5f)
        val first = listOf("to" to 59.5f, "prepare" to 59.9f, "themselves" to 60.4f, "to" to 60.8f, "a" to 61f).map { HeardWord(it.first, it.second) }
        transcript.read(first, 61.4f)
        transcript.read(first, 61.4f)
        val corrected = listOf("to" to 59.5f, "prepare" to 60.1f, "themselves" to 60.6f, "a" to 61.2f, "week" to 61.3f, "ago" to 61.6f).map { HeardWord(it.first, it.second) }
        val shown = transcript.read(corrected, 62f).words + transcript.read(corrected, 63f).words
        assertEquals("a week ago", shown.text())
    }

    @Test fun aRepeatedPhraseInTheSpeechIsKept() {
        val transcript = RollingTranscript(0.5f)
        transcript.read(heard("we shall we shall go"), 3f)
        transcript.read(heard("we shall we shall go"), 3f)
        val longer = heard("we shall we shall go on")
        transcript.read(longer, 4f)
        assertEquals("on", transcript.read(longer, 4f).words.text())
    }

    @Test fun aLaterReadingExtendsAWordCutOffAtAPause() {
        val transcript = RollingTranscript(0.5f)
        transcript.pause(heard("the British expedition"))
        transcript.read(heard("British expeditionary force north of", 0.5f), 4f)
        assertEquals("force north of", transcript.read(heard("British expeditionary force north of", 0.5f), 4f).words.text())
    }

    @Test fun aFullStopAtAPauseIsHeldBackUntilTheSpeechResumes() {
        val transcript = RollingTranscript(0.5f)
        assertEquals("fix this afternoon", transcript.pause(heard("fix this afternoon.")).words.text())
        val update = transcript.read(heard("this afternoon as the occasion", 0.5f), 2.6f)
        assertNull(update.revisedLast)
    }

    @Test fun aRealSentenceEndIsRestoredWhenTheNextReadingHearsIt() {
        val transcript = RollingTranscript(0.5f)
        transcript.pause(heard("we came"))
        assertEquals("came.", transcript.read(heard("came. They left", 0.5f), 2.6f).revisedLast)
    }

    @Test fun longSilenceConfirmsTheHeldFullStop() {
        val transcript = RollingTranscript(0.5f)
        transcript.pause(heard("we came."))
        assertEquals("came.", transcript.forget(4f, 1f).revisedLast)
    }

    @Test fun laterSpeechOwesNothingToWhatCameBeforeTheSilence() {
        val transcript = RollingTranscript(0.5f)
        transcript.pause(heard("we came"))
        transcript.forget(10f, 1f)
        assertEquals(9f, transcript.windowStartSeconds)
        assertEquals("we", transcript.pause(heard("we", 10f)).words.text())
    }

    @Test fun theWindowKeepsSomeContextWhenItIsTrimmed() {
        val transcript = RollingTranscript(0.5f)
        val words = heard((1..30).joinToString(" ") { "w$it" })
        transcript.read(words, 16f)
        assertEquals(8.58f, transcript.windowStartSeconds, 0.01f)
    }

    @Test fun speechWithNoWordsStillCannotGrowTheWindowForever() {
        val transcript = RollingTranscript(0.5f)
        transcript.read(emptyList(), 30f)
        assertEquals(18f, transcript.windowStartSeconds)
    }

    /** Hears [rolling] until its second call, then what each later reading hears. */
    private class Reads(private val rolling: String, private val again: String) : SpeechRecognizer {
        private var count = 0
        var lastSize = 0
        override suspend fun recognize(utterance: Utterance) = ""
        override suspend fun words(utterance: Utterance): List<HeardWord> {
            lastSize = utterance.samples.size
            val text = if (count++ < 2) rolling else again
            return text.split(' ').filter { it.isNotEmpty() }.mapIndexed { index, word -> HeardWord(word, index * 0.5f) }
        }
        override fun close() {}
    }

    @Test fun tooLittleAudioAfterASeekIsNotRead() = runTest {
        val recognizer = object : SpeechRecognizer {
            override suspend fun recognize(utterance: Utterance) = ""
            override suspend fun words(utterance: Utterance): List<HeardWord> = error("The recognizer fails on too little audio")
            override fun close() {}
        }
        assertEquals(0, RollingReader(recognizer).step(Utterance(FloatArray(0), 0), true, 0)?.cues?.size ?: 0)
    }

    @Test fun aShortSentenceCanBeShownOnceTheSpeakerStops() = runTest {
        val reader = RollingReader(Reads("Newton explored gravity.", "Newton explored gravity."))
        val audio = Utterance(FloatArray(3 * SAMPLE_RATE), 0)
        reader.step(audio, true, 3L * SAMPLE_RATE)
        reader.step(audio, true, 3L * SAMPLE_RATE)
        assertEquals("Newton explored gravity", reader.step(audio, false, 3L * SAMPLE_RATE)?.stoppedAt?.sentenceEnglish?.trimEnd('.'))
    }

    @Test fun soundWithNoNewWordsIsAStopEvenWithoutAFullStop() = runTest {
        val reader = RollingReader(Reads("Where I am", "Where I am"))
        val audio = Utterance(FloatArray(3 * SAMPLE_RATE), 0)
        reader.step(audio, true, 3L * SAMPLE_RATE)
        assertEquals("Where I am", reader.step(audio, true, 3L * SAMPLE_RATE)?.stoppedAt?.english)
    }

    @Test fun soundWithNoWordsForLongEndsTheSentence() = runTest {
        val reader = RollingReader(Reads("Where I am", "Where I am"))
        val audio = Utterance(FloatArray(6 * SAMPLE_RATE), 0)
        reader.step(audio, true, 6L * SAMPLE_RATE)
        assertEquals("Where I am", reader.step(audio, true, 6L * SAMPLE_RATE)?.cues?.single { it.isFinal }?.english)
    }

    private suspend fun closeOneSentence(recognizer: SpeechRecognizer): List<SubtitleCue> {
        val reader = RollingReader(recognizer)
        val audio = Utterance(FloatArray(3 * SAMPLE_RATE), 0)
        reader.step(audio, true, 3L * SAMPLE_RATE)
        return reader.step(audio, true, 3L * SAMPLE_RATE)!!.cues
    }

    @Test fun aClosedSentenceIsReadAgainAndTheNewReadingIsKept() = runTest {
        val closed = closeOneSentence(Reads("They sold minds. Then", "They sowed mines. Then")).first { it.isFinal }
        assertEquals("They sowed mines.", closed.english)
    }

    @Test fun theNextSentencesFirstWordIsNotReadIntoThisOne() = runTest {
        val shifted = object : SpeechRecognizer {
            private var count = 0
            override suspend fun recognize(utterance: Utterance) = ""
            override suspend fun words(utterance: Utterance): List<HeardWord> {
                val earlyBy = if (count++ < 2) 0f else 0.3f
                return "They sold minds. Then".split(' ').mapIndexed { index, word -> HeardWord(word, maxOf(0f, index * 0.5f - earlyBy)) }
            }
            override fun close() {}
        }
        assertEquals("They sold minds.", closeOneSentence(shifted).first { it.isFinal }.english)
    }

    @Test fun anOpenSentenceIsReadWholeAtAPause() = runTest {
        val reader = RollingReader(Reads("We all sold the magnetic minds here today", "We all sowed the magnetic mines here today"))
        val audio = Utterance(FloatArray(6 * SAMPLE_RATE), 0)
        reader.step(audio, true, 6L * SAMPLE_RATE)
        reader.step(audio, true, 6L * SAMPLE_RATE)
        val paused = reader.step(audio, false, 6L * SAMPLE_RATE)!!
        assertEquals("We all sowed the magnetic mines here today", paused.atPause?.english)
    }

    @Test fun wordsStoppingCountAsAPauseEvenIfTheDetectorStillHearsSpeech() = runTest {
        val reader = RollingReader(Reads("We all sold the magnetic minds here today.", "We all sowed the magnetic mines here today."))
        val audio = Utterance(FloatArray(6 * SAMPLE_RATE), 0)
        reader.step(audio, true, 6L * SAMPLE_RATE)
        assertEquals("We all sowed the magnetic mines here today.", reader.step(audio, true, 6L * SAMPLE_RATE)?.atPause?.english)
    }

    @Test fun aFullStopAfterAWordLikeOnSoonCountsAsAPause() = runTest {
        val reader = RollingReader(Reads("I walked on my bed with my shoes on.", "I walked on my bed with my shoes on."))
        val audio = Utterance(FloatArray(5 * SAMPLE_RATE), 0)
        // "on." starts at 4 s: 0.7 s on, a stop, though not yet the 1.5 s that any last word gets.
        reader.step(audio, true, 4L * SAMPLE_RATE + SAMPLE_RATE * 7 / 10)
        assertEquals("I walked on my bed with my shoes on.", reader.step(audio, true, 4L * SAMPLE_RATE + SAMPLE_RATE * 7 / 10)?.atPause?.english)
    }

    @Test fun aSentenceReadAtAPauseKeepsThatTextWhenItCloses() = runTest {
        val reader = RollingReader(Reads("We all sold the magnetic minds here today.", "We all sowed the magnetic mines here today."))
        val audio = Utterance(FloatArray(6 * SAMPLE_RATE), 0)
        reader.step(audio, true, 6L * SAMPLE_RATE)
        reader.step(audio, true, 6L * SAMPLE_RATE)
        assertEquals("We all sowed the magnetic mines here today.", reader.finish().cues.first { it.isFinal }.english)
    }

    @Test fun aSecondReadingThatLostTheSentenceIsIgnored() = runTest {
        val closed = closeOneSentence(Reads("They sold minds. Then", "")).first { it.isFinal }
        assertEquals("They sold minds.", closed.english)
    }

    @Test fun aSentenceStaysInTheAudioUntilItCloses() = runTest {
        val reader = RollingReader(Reads("We came", "We came"))
        val audio = Utterance(FloatArray(3 * SAMPLE_RATE), 0)
        reader.step(audio, true, 3L * SAMPLE_RATE)
        reader.step(audio, true, 3L * SAMPLE_RATE)
        assertEquals(0f, reader.keepFromSeconds)
    }

    private fun update(vararg words: String) = RollingTranscript.Update(words.map { HeardWord(it, 1f) })

    @Test fun aSentenceGrowsAndThenClosesWithOneId() {
        val sentences = SpokenSentences()
        val growing = sentences.take(update("We", "came")).single()
        assertFalse(growing.isFinal)
        val closed = sentences.take(update("home.", "They")).toList()
        assertEquals(listOf("We came home.", "They"), closed.map { it.english })
        assertEquals(listOf(true, false), closed.map { it.isFinal })
        assertEquals(growing.id, closed[0].id)
        assertEquals(growing.id + 1, closed[1].id)
    }

    @Test fun aSentenceClosesAsSoonAsTheNextWordIsHeard() {
        val sentences = SpokenSentences()
        val cues = sentences.take(RollingTranscript.Update(listOf(HeardWord("We", 0f), HeardWord("came.", 0.5f)), nextStartSeconds = 1f))
        assertEquals("We came.", cues.single().english)
        assertTrue(cues.single().isFinal)
    }

    @Test fun anUnfinishedSentenceStaysOpenWhenTheNextWordIsHeard() {
        val cue = SpokenSentences().take(RollingTranscript.Update(listOf(HeardWord("We", 0f), HeardWord("came", 0.5f)), nextStartSeconds = 1f)).single()
        assertFalse(cue.isFinal)
    }

    @Test fun anAbbreviationDoesNotCloseASentence() {
        assertFalse(SpokenSentences().take(update("Mr.", "Speaker")).any { it.isFinal })
    }

    @Test fun aFullStopTheNextReadingTakesBackDoesNotCloseTheSentence() {
        val sentences = SpokenSentences()
        sentences.take(update("We", "came."))
        val cues = sentences.take(RollingTranscript.Update(listOf(HeardWord("home", 1f)), "came"))
        assertEquals("We came home", cues.single().english)
        assertFalse(cues.single().isFinal)
    }

    @Test fun aRestoredFullStopClosesTheSentenceWhenTheNextWordArrives() {
        val sentences = SpokenSentences()
        sentences.take(update("We", "came"))
        val cues = sentences.take(RollingTranscript.Update(listOf(HeardWord("They", 1f)), "came."))
        assertEquals(listOf("We came.", "They"), cues.map { it.english })
        assertEquals(listOf(true, false), cues.map { it.isFinal })
    }

    private fun spoken(count: Int, gap: Float = 0.3f) = List(count) { HeardWord("word", it * gap) }

    private fun held() = SpokenSentences()

    @Test fun aSentenceRunsPastAnyLineLength() {
        val sentences = held()
        assertFalse(sentences.take(update(*Array(50) { "word" })).any { it.isFinal })
    }

    @Test fun aVeryLongSentenceEndsAtItsLastComma() {
        val words = List(40) { HeardWord("word,", it * 0.3f) } + List(22) { HeardWord("word", 12f + it * 0.3f) }
        val closed = held().take(RollingTranscript.Update(words)).first { it.isFinal }
        assertEquals(40, closed.english.split(' ').size)
    }

    @Test fun aSentenceDoesNotEndOnASmallWord() {
        val sentences = held()
        val cues = sentences.take(RollingTranscript.Update(listOf(HeardWord("Whole", 0f), HeardWord("of", 0.3f), HeardWord("the.", 0.6f), HeardWord("British", 1f))))
        assertFalse(cues.any { it.isFinal })
    }

    @Test fun aFullStopAfterAWordASentenceCanEndOnEndsItBeforeACapital() {
        val cues = held().take(RollingTranscript.Update(listOf(HeardWord("it", 0f), HeardWord("needs", 0.2f), HeardWord("to", 0.4f), HeardWord("be.", 0.5f),
            HeardWord("I", 0.7f), HeardWord("see.", 0.9f))))
        assertEquals("it needs to be.", cues.first().english)
        assertTrue(cues.first().isFinal)
        val sentences = held()
        sentences.take(RollingTranscript.Update(listOf(HeardWord("further", 0f), HeardWord("back", 0.2f), HeardWord("in.", 0.4f))))
        assertTrue(sentences.take(RollingTranscript.Update(emptyList(), nextStartSeconds = 0.6f, nextText = "And")).single().isFinal)
    }

    @Test fun aFullStopAfterAWordNothingEndsOnKeepsTheSentenceOpen() {
        assertFalse(held().take(RollingTranscript.Update(listOf(HeardWord("one", 0f), HeardWord("of.", 0.3f), HeardWord("Them", 0.6f)))).any { it.isFinal })
        assertFalse(held().take(RollingTranscript.Update(listOf(HeardWord("back", 0f), HeardWord("in.", 0.3f), HeardWord("and", 0.6f)))).any { it.isFinal })
    }

    @Test fun aFullStopBeforeASmallLetterDoesNotEndASentence() {
        assertFalse(held().take(RollingTranscript.Update(listOf(HeardWord("hard", 0f), HeardWord("luck.", 0.3f), HeardWord("to", 0.6f)))).any { it.isFinal })
        val sentences = held()
        sentences.take(RollingTranscript.Update(listOf(HeardWord("hard", 0f), HeardWord("luck.", 0.3f))))
        assertFalse(sentences.take(RollingTranscript.Update(emptyList(), nextStartSeconds = 0.6f, nextText = "to")).any { it.isFinal })
    }

    @Test fun aPauseAndACapitalEndASentence() {
        val cues = held().take(RollingTranscript.Update(listOf(HeardWord("explored", 0f), HeardWord("gravity", 0.4f), HeardWord("Last", 1.2f), HeardWord("month", 1.5f))))
        assertEquals("explored gravity.", cues.first().english)
        assertTrue(cues.first().isFinal)
    }

    @Test fun aPauseBeforeANameInsideASentenceDoesNotEndIt() {
        assertFalse(held().take(RollingTranscript.Update(listOf(HeardWord("Thank", 0f), HeardWord("you,", 0.3f), HeardWord("Webb", 1.2f), HeardWord("today", 1.5f)))).any { it.isFinal })
    }

    @Test fun aPauseBeforeANameAfterAllDoesNotEndASentence() {
        assertFalse(held().take(RollingTranscript.Update(listOf(HeardWord("from", 0f), HeardWord("all", 0.3f), HeardWord("America", 1.2f), HeardWord("today", 1.5f)))).any { it.isFinal })
    }

    @Test fun aPauseBeforeAndDoesNotEndASentence() {
        val words = List(8) { HeardWord("word", it * 0.3f) } + HeardWord("and", 3.5f) + HeardWord("more", 3.8f)
        assertFalse(held().take(RollingTranscript.Update(words)).any { it.isFinal })
    }

    @Test fun aPauseBeforeIDoesNotEndASentence() {
        assertFalse(held().take(RollingTranscript.Update(listOf(HeardWord("then", 0f), HeardWord("I", 1.2f), HeardWord("said", 1.5f)))).any { it.isFinal })
    }

    @Test fun eachSettledWordGoesOnTheLineAtOnce() {
        val sentences = held()
        sentences.take(RollingTranscript.Update(spoken(2)))
        assertEquals(2, sentences.ready()?.english?.split(' ')?.size)
        sentences.take(RollingTranscript.Update(listOf(HeardWord("the", 0.8f))))
        assertEquals("word word the", sentences.ready()?.english)
        assertNull(sentences.ready())
    }

    @Test fun aFullStopAtTheEdgeOfWhatWasHeardIsNotShown() {
        val sentences = held()
        sentences.take(RollingTranscript.Update(spoken(4) + HeardWord("word.", 1.2f)))
        assertEquals("word word word word word", sentences.ready()?.english)
    }


    @Test fun lookingAtTheOpenSentenceDoesNotCountAsShowingIt() {
        val sentences = held()
        sentences.take(RollingTranscript.Update(spoken(5)))
        sentences.open()
        assertEquals(5, sentences.ready()?.english?.split(' ')?.size)
    }

    private fun steadyAfter(vararg words: String): String? {
        val sentences = held()
        sentences.take(RollingTranscript.Update(words.mapIndexed { index, word -> HeardWord(word, index * 0.5f) }))
        return sentences.ready()?.sentenceEnglish
    }

    @Test fun aNumberWaitsForWhatComesNext() {
        assertEquals("perhaps", steadyAfter("perhaps", "twenty", "or", "thirty"))
        assertEquals("perhaps twenty or thirty thousand men", steadyAfter("perhaps", "twenty", "or", "thirty", "thousand", "men"))
    }

    @Test fun aNegationWaitsForWhatComesNext() {
        assertEquals("so it", steadyAfter("so", "it", "was", "not"))
        assertEquals("we do not stay", steadyAfter("we", "do", "not", "stay"))
    }

    @Test fun aSmallWordAtTheEndWaitsForWhatComesNext() {
        assertEquals("we met", steadyAfter("we", "met", "at", "the"))
    }

    @Test fun aWordTheReadingsDisagreedOnWaitsForTwoMoreWords() {
        val sentences = held()
        sentences.take(RollingTranscript.Update(listOf(HeardWord("Amiens", 0f), HeardWord("Abbeville", 0.4f, unsure = true), HeardWord("gap", 0.8f))))
        assertEquals("Amiens", sentences.ready()?.sentenceEnglish)
        sentences.take(RollingTranscript.Update(listOf(HeardWord("today", 1.2f), HeardWord("fine", 1.6f))))
        assertEquals("Amiens Abbeville gap today fine", sentences.ready()?.sentenceEnglish)
    }

    @Test fun finishingClosesWhatIsOpenOnce() {
        val sentences = SpokenSentences()
        sentences.take(update("We", "came"))
        assertEquals("We came", sentences.finish().single().english)
        assertTrue(sentences.finish().isEmpty())
    }
}
