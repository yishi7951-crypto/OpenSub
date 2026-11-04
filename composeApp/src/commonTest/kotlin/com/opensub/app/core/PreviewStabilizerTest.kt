package com.opensub.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewStabilizerTest {
    @Test fun wordsAppearOnlyAfterTwoReadingsAgree() {
        val stabilizer = PreviewStabilizer()
        assertNull(stabilizer.accept(1, "We choose"))
        assertEquals("We choose", stabilizer.accept(1, "We choose to go"))
        assertEquals("We choose to go", stabilizer.accept(1, "We choose to go to the moon"))
    }

    @Test fun aChangedWordCannotShrinkWhatIsShown() {
        val stabilizer = PreviewStabilizer()
        stabilizer.accept(1, "the fact that")
        assertEquals("the fact", stabilizer.accept(1, "the fact it"))
        assertNull(stabilizer.accept(1, "the fast one"))
    }

    @Test fun punctuationAndCaseDoNotCountAsChanges() {
        val stabilizer = PreviewStabilizer()
        stabilizer.accept(1, "Hello, world")
        assertEquals("hello world", stabilizer.accept(1, "hello world."))
    }

    @Test fun aNewSentenceStartsFresh() {
        val stabilizer = PreviewStabilizer()
        stabilizer.accept(1, "first words")
        stabilizer.accept(1, "first words")
        assertNull(stabilizer.accept(2, "second words"))
        assertEquals("second words", stabilizer.accept(2, "second words"))
    }

    @Test fun aStableCorrectionWithTheSameWordCountIsPublished() {
        val stabilizer = PreviewStabilizer()
        stabilizer.accept(1, "They sold magnetic mines")
        stabilizer.accept(1, "They sold magnetic mines")
        assertNull(stabilizer.accept(1, "They sowed magnetic mines"))
        assertEquals("They sowed magnetic mines", stabilizer.accept(1, "They sowed magnetic mines"))
    }
}
