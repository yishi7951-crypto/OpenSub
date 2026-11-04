package com.opensub.app.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SentenceWindowTest {
    private fun utterance(seconds: Int = 1, startMs: Long = 0) = Utterance(FloatArray(seconds * SAMPLE_RATE), startMs * SAMPLE_RATE / 1000)
    private fun cue(id: Long, startMs: Long, english: String) = SubtitleCue(id, startMs, startMs + 1000, english, 0)

    /** Places a word every 0.8 seconds. Audio positions match the cue's sample clock. */
    private class Hearing(private val heard: () -> List<String>) : SpeechRecognizer {
        var calls = 0
        override suspend fun recognize(utterance: Utterance) = ""
        override suspend fun words(utterance: Utterance): List<HeardWord> {
            calls++
            return heard().mapIndexed { index, word -> HeardWord(word, index * 0.8f) }
        }
        override fun close() {}
    }

    @Test fun aFullStopAtAJoinIsIgnoredWhenTheModelHearsAcrossIt() = runTest {
        val window = SentenceWindow(Hearing { listOf("Only", "last", "week", "we", "developed") })
        window.add(cue(1, 0, "Only last week."), utterance())
        val sentences = window.add(cue(2, 1100, "We developed."), utterance(3, 1100))
        assertEquals("Only last week we developed", sentences.single().english)
        assertEquals(1L, sentences.single().id)
        assertEquals(2100L, sentences.single().endMs)
    }

    @Test fun aFullStopTheModelKeepsAcrossAJoinClosesTheSentence() = runTest {
        val window = SentenceWindow(Hearing { listOf("We", "came.", "They", "left.") })
        window.add(cue(1, 0, "We came"), utterance())
        val sentences = window.add(cue(2, 1100, "They left."), utterance(2, 1100))
        assertEquals(listOf("We came.", "They left."), sentences.map { it.english })
        assertEquals(listOf(1L, 2L), sentences.map { it.id })
    }

    @Test fun theNextSentenceDoesNotReachBackIntoAClosedOne() = runTest {
        val heard = ArrayDeque(listOf(listOf("We", "came.", "They", "left."), listOf("They", "left", "again")))
        val recognizer = Hearing { heard.removeFirst() }
        val window = SentenceWindow(recognizer)
        window.add(cue(1, 0, "We came"), utterance())
        window.add(cue(2, 1100, "They left."), utterance(2, 1100))
        val sentences = window.add(cue(3, 2200, "Again."), utterance(startMs = 2200))
        assertEquals(2L, sentences.single().id)
    }

    @Test fun aLongPauseStartsANewSentenceWithoutRecognizingAgain() = runTest {
        val recognizer = Hearing { listOf("never") }
        val window = SentenceWindow(recognizer)
        window.add(cue(1, 0, "Hello."), utterance())
        val sentences = window.add(cue(2, 5000, "Goodbye."), utterance(startMs = 5000))
        assertEquals("Goodbye.", sentences.single().english)
        assertEquals(0, recognizer.calls)
    }

    @Test fun theWindowNeverGrowsPastItsLimit() = runTest {
        val recognizer = Hearing { listOf("one", "two") }
        val window = SentenceWindow(recognizer)
        window.add(cue(1, 0, "One.").copy(endMs = 8000), utterance(8))
        val sentences = window.add(cue(2, 8100, "Two"), utterance(8, 8100))
        assertEquals(2L, sentences.single().id)
        assertEquals(0, recognizer.calls)
    }

    @Test fun aLowerCasePieceRejoinsTheSentenceDespiteAFullStopAtTheJoin() = runTest {
        val window = SentenceWindow(Hearing { listOf("They", "came.", "And", "left.") })
        window.add(cue(1, 0, "They came."), utterance())
        val sentences = window.add(cue(2, 1100, "and left."), utterance(2, 1100))
        assertEquals("They came and left.", sentences.single().english)
        assertEquals(1L, sentences.single().id)
    }

    @Test fun anUnfinishedSentenceCarriesOnWhenTheWindowIsFull() = runTest {
        val heard = ArrayDeque(listOf(listOf("is", "doubling", "again", "soon.")))
        val recognizer = Hearing { heard.removeFirst() }
        val window = SentenceWindow(recognizer)
        window.add(cue(1, 0, "Our manpower.").copy(endMs = 8000), utterance(8))
        val carried = window.add(cue(2, 8100, "is doubling").copy(endMs = 16100), utterance(8, 8100)).single()
        assertEquals("Our manpower is doubling", carried.english)
        assertEquals(1L, carried.id)
        assertEquals(0, recognizer.calls)
        val next = window.add(cue(3, 16200, "again soon."), utterance(2, 16200)).single()
        assertEquals("Our manpower is doubling again soon.", next.english)
        assertEquals(1L, next.id)
    }

    @Test fun aCarriedSentenceStopsGrowingAtItsWordLimit() = runTest {
        val window = SentenceWindow(Hearing { emptyList() })
        window.add(cue(1, 0, List(40) { "word" }.joinToString(" ")).copy(endMs = 8000), utterance(8))
        val sentences = window.add(cue(2, 8100, List(20) { "more" }.joinToString(" ")), utterance(8, 8100))
        assertEquals(2L, sentences.single().id)
    }

    @Test fun aWordCrossingACutIsNotRepeatedWhenItsAudioLeavesTheWindow() = runTest {
        val window = SentenceWindow(Hearing { emptyList() })
        window.add(cue(1, 0, "would have to capitulate").copy(endMs = 8000), utterance(8).copy(cutMidSpeech = true))
        val merged = window.add(cue(2, 8000, "capitulate for lack of food"), utterance(8, 8000)).single()
        assertEquals("would have to capitulate for lack of food", merged.english)
    }

    @Test fun aRecognizerWithoutTimingKeepsThePunctuationRules() = runTest {
        val recognizer = object : SpeechRecognizer {
            override suspend fun recognize(utterance: Utterance) = ""
            override fun close() {}
        }
        val window = SentenceWindow(recognizer)
        window.add(cue(1, 0, "We have"), utterance())
        assertEquals("We have reached", window.add(cue(2, 1100, "reached"), utterance(startMs = 1100)).single().english)
    }

    @Test fun tokensBecomeWordsWithTheStartOfTheirFirstToken() {
        val words = heardWords(listOf(" Wel", "l", ",", " we", " met", "."), listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f))
        assertEquals(listOf(HeardWord("Well,", 0.1f), HeardWord("we", 0.4f), HeardWord("met.", 0.5f)), words)
        assertEquals(listOf("Hi", "there"), heardWords(listOf("▁Hi", "▁there"), listOf(0f, 1f)).map { it.text })
        assertTrue(heardWords(listOf("Hi"), emptyList()).isEmpty())
    }

    @Test fun textSplitsIntoSentences() {
        assertEquals(listOf("We came.", "They left!", "Why?"), splitSentences("We came. They left! Why?"))
        assertEquals(listOf("It rose 3.5 percent in the U.S. today."), splitSentences("It rose 3.5 percent in the U.S. today."))
        assertEquals(listOf("Mr. Smith met Dr. Jones."), splitSentences("Mr. Smith met Dr. Jones."))
        assertEquals(listOf("I was... Well, I left."), splitSentences("I was... Well, I left."))
        assertEquals(listOf("It ended. and went on"), splitSentences("It ended. and went on"))
        assertEquals(listOf("So did I.", "We left."), splitSentences("So did I. We left."))
        assertEquals(emptyList(), splitSentences("  "))
    }

    @Test fun eachSentenceIsTranslatedAloneAndOnlyOnce() = runTest {
        val seen = ArrayList<String>()
        val model = object : Translator {
            override suspend fun translate(english: String): String { seen += english; return if (english.startsWith("We")) "我们来了" else "他们走了。" }
            override fun close() {}
        }
        val translator = SentenceTranslator(CachingTranslator(model))
        assertEquals("我们来了", translator.translate("We came."))
        assertEquals("我们来了。他们走了。", translator.translate("We came. They left."))
        assertEquals(listOf("We came.", "They left."), seen)
    }

    /** Answers in order, and like a real model keeps whatever it is told to continue from. */
    private class Scripted(vararg answers: String) : Translator {
        private val queue = ArrayDeque(answers.toList())
        val told = ArrayList<String>()
        override suspend fun translate(english: String) = queue.removeFirst()
        override suspend fun continueFrom(english: String, shown: String): String {
            told += shown
            return shown + queue.removeFirst().removePrefix(shown)
        }
        override fun close() {}
    }

    @Test fun aGrowingSentenceContinuesFromItsLastClause() = runTest {
        val model = Scripted("敌人进攻，来自四方", "敌人进攻，来自东西两侧，猛烈", "敌人进攻，来自东西两侧，猛烈的海滩")
        val translator = SentenceTranslator(model)
        translator.translate("The enemy attacked from all")
        translator.translate("The enemy attacked from all sides")
        assertEquals("敌人进攻，来自东西两侧，猛烈的海滩", translator.translate("The enemy attacked from all sides and the beach"))
        assertEquals(listOf("敌人进攻，", "敌人进攻，来自东西两侧，"), model.told)
    }

    @Test fun aDraftWithNoClauseYetCanStillChangeCompletely() = runTest {
        val model = Scripted("第一版", "另一种译法")
        val translator = SentenceTranslator(model)
        translator.translate("We went to the")
        assertEquals("另一种译法", translator.translate("We went to the sea"))
        assertTrue(model.told.isEmpty())
    }

    @Test fun aDifferentSentenceStartsAfresh() = runTest {
        val model = Scripted("他们来了，很快", "我们走了")
        val translator = SentenceTranslator(model)
        translator.translate("They came very quickly.")
        assertEquals("我们走了", translator.translate("Nobody stayed behind after that."))
        assertTrue(model.told.isEmpty())
    }

    private class Continuing(vararg answers: String) : Translator {
        private val queue = ArrayDeque(answers.toList())
        val asked = ArrayList<Pair<String, String>>()
        override val continues get() = true
        override suspend fun translate(english: String) = queue.removeFirst()
        override suspend fun continueFrom(english: String, shown: String): String {
            asked += english to shown
            return shown + queue.removeFirst()
        }
        override fun close() {}
    }

    @Test fun aSentenceCutShortCarriesOnFromItsFirstPart() = runTest {
        val model = Continuing("敌人开始炮击海滩……", "只有这些地方才能让船只靠近。")
        val translator = SentenceTranslator(model)
        translator.translate("The enemy began to fire upon the beaches")
        assertEquals("只有这些地方才能让船只靠近。", translator.translate("by which alone the shipping could approach."))
        assertEquals("The enemy began to fire upon the beaches by which alone the shipping could approach." to "敌人开始炮击海滩，", model.asked.single())
    }

    @Test fun theSameWordsWithOtherPunctuationAreNotTranslatedAgain() = runTest {
        val model = Continuing("我们今天都来了。")
        val translator = SentenceTranslator(model)
        translator.translate("We all came here today.")
        assertEquals("我们今天都来了。", translator.translate("We all, came here today."))
    }

    @Test fun anUnfinishedPartDoesNotKeepTheEndingInventedAtAPause() = runTest {
        val model = Continuing("敌人开始炮击海滩，并且结束了。", "而且")
        val translator = SentenceTranslator(model)
        translator.translate("The enemy began to fire upon the beaches.")
        assertEquals("敌人开始炮击海滩，", translator.translate("The enemy began to fire upon the beaches"))
    }

    @Test fun aFinishedSentenceIsNotCarriedInto() = runTest {
        val model = Continuing("我们来了。", "他们走了。")
        val translator = SentenceTranslator(model)
        translator.translate("We came here today.")
        assertEquals("他们走了。", translator.translate("Nobody stayed behind after that."))
        assertTrue(model.asked.isEmpty())
    }

    @Test fun aSentenceReadAgainWithADifferentWordIsTranslatedAfresh() = runTest {
        val model = Scripted("他们出售了磁性思维，还有更多", "他们埋设了磁雷，还有更多")
        val translator = SentenceTranslator(model)
        translator.translate("They sold magnetic minds and more")
        assertEquals("他们埋设了磁雷，还有更多", translator.translate("They sowed magnetic mines and more."))
        assertTrue(model.told.isEmpty())
    }

    @Test fun aRevisedWordStillCountsAsTheSameSentence() {
        assertTrue(sameSentence("They sold magnetic mines", "They sowed magnetic mines in the channels."))
        assertFalse(sameSentence("We came.", "We left."))
        assertFalse(sameSentence("They sold magnetic mines", "Nobody stayed behind after that"))
    }

    @Test fun someoneAddressedMovesToTheFrontBeforeTranslating() {
        assertEquals("Mr Speaker, When a week ago I asked the House.", vocativeFirst("When a week ago, Mr Speaker, I asked the House."))
        assertEquals("Ladies and gentlemen, I am here today.", vocativeFirst("I am here, ladies and gentlemen, today."))
        assertEquals("We met Mr Speaker, and left.", vocativeFirst("We met Mr Speaker, and left."))
    }

    @Test fun aDecoderLoopIsFound() {
        assertEquals(0, repeatedTail(listOf(1, 2, 3, 4, 5, 6)))
        assertEquals(2, repeatedTail(listOf(1, 7, 7, 7)))
        assertEquals(4, repeatedTail(listOf(9, 1, 2, 1, 2, 1, 2)))
        assertEquals(0, repeatedTail(listOf(1, 2, 1, 2)))
    }
}
