package com.opensub.app.core

import kotlin.test.*

class RecognitionContextTest {
    @Test fun contiguousPiecesHaveNoInventedPause() {
        val audio = joinAudio(listOf(Utterance(floatArrayOf(1f, 2f), 100), Utterance(floatArrayOf(3f), 102)))
        assertEquals(100, audio.startSample)
        assertContentEquals(floatArrayOf(1f, 2f, 3f), audio.samples)
    }

    @Test fun realPausesAndOverlappingSamplesKeepTheirOriginalPositions() {
        val audio = joinAudio(listOf(Utterance(floatArrayOf(1f, 2f), 100), Utterance(floatArrayOf(3f, 4f), 104), Utterance(floatArrayOf(4f, 5f), 105)))
        assertContentEquals(floatArrayOf(1f, 2f, 0f, 0f, 3f, 4f, 5f), audio.samples)
    }

    @Test fun previewsAndFinalKeepTheSameBoundedLeftContext() {
        val context = RecognitionContext()
        context.input(Utterance(FloatArray(7 * SAMPLE_RATE) { 1f }, 0))
        val preview = context.input(Utterance(FloatArray(SAMPLE_RATE) { 2f }, 7L * SAMPLE_RATE))
        val final = context.input(Utterance(FloatArray(2 * SAMPLE_RATE) { 2f }, 7L * SAMPLE_RATE))
        assertEquals(4L * SAMPLE_RATE, preview.audio.startSample)
        assertEquals(3f, preview.newAudioSeconds)
        assertEquals(preview.newAudioSeconds, final.newAudioSeconds)
        assertEquals(5 * SAMPLE_RATE, final.audio.samples.size)
        assertEquals("new words", final.text(listOf(HeardWord("old", 2.5f), HeardWord("new", 3f), HeardWord("words", 3.5f))))
    }

    @Test fun aLongPauseAndARewoundClockDiscardOldContext() {
        val context = RecognitionContext()
        context.input(Utterance(FloatArray(SAMPLE_RATE), 0))
        assertEquals(0f, context.input(Utterance(FloatArray(SAMPLE_RATE), 3L * SAMPLE_RATE)).newAudioSeconds)
        assertEquals(0f, context.input(Utterance(FloatArray(SAMPLE_RATE), 0)).newAudioSeconds)
    }

    @Test fun earlyWordTimingDoesNotLoseTheFirstWordOrRepeatLeftContext() {
        val input = RecognitionInput(Utterance(FloatArray(1), 0), 3f, "to the")
        assertEquals("the moon", input.text(listOf(HeardWord("the", 2.9f), HeardWord("the", 3.05f), HeardWord("moon", 3.4f))))
        assertEquals("Governor", input.text(listOf(HeardWord("President", 1f), HeardWord("Governor", 2.75f))))
    }

    @Test fun matchingThePreviousSuffixKeepsANewWordDespiteItsCoarseTiming() {
        val input = RecognitionInput(Utterance(FloatArray(1), 0), 3f, "Congressman Thomas.")
        assertEquals("Senator Wiley", input.text(listOf(HeardWord("Congressman", 0.5f), HeardWord("Thomas,", 1f), HeardWord("Senator", 2.5f), HeardWord("Wiley", 3.4f))))
    }

    @Test fun rightContextCompletesTheLastWordWithoutEmittingTheNextOneEarly() {
        val input = RecognitionInput(Utterance(FloatArray(SAMPLE_RATE), 0, true, FloatArray(SAMPLE_RATE / 3)))
        assertEquals("capitulate", input.text(listOf(HeardWord("capitulate", 0.9f), HeardWord("for", 1.1f))))
        assertEquals(1000, input.audio.endMs)
    }
}
