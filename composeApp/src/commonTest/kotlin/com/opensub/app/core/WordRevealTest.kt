package com.opensub.app.core

import kotlin.test.*

class WordRevealTest {
    @Test fun wordsAppearInOrderAndAnimationDoesNotHoldAWholeSentence() {
        val reveal = WordReveal()
        reveal.target(1, "I can hear you.", 0)
        val first = reveal.frame(0)
        assertEquals("I", first.visibleText)
        assertTrue(first.animating)
        val finished = reveal.frame(320)
        assertEquals("I can hear you.", finished.visibleText)
        assertTrue(finished.words.all { it.opacity == 1f })
        assertFalse(finished.animating)
    }
    @Test fun recognitionCorrectionsRetainPreviouslyVisibleWords() {
        val reveal = WordReveal()
        reveal.target(1, "I can see you", 0)
        reveal.frame(320)
        reveal.target(1, "I can hear you clearly.", 400)
        val updated = reveal.frame(400)
        assertEquals("I can hear you", updated.visibleText.substringBeforeLast(' '))
        assertTrue(updated.words.take(4).all { it.opacity == 1f })
        assertEquals("I can hear you clearly.", reveal.frame(720).visibleText)
    }
    @Test fun newRecognitionReplacesPendingAnimationInsteadOfQueuingOldText() {
        val reveal = WordReveal()
        reveal.target(1, "The first version is still arriving", 0)
        reveal.frame(20)
        reveal.target(1, "The corrected version.", 30)
        assertEquals("The corrected version.", reveal.frame(350).visibleText)
        reveal.target(2, "Another sentence.", 400)
        assertEquals("Another", reveal.frame(400).visibleText)
    }
    @Test fun veryLargeBatchesCatchUpWithinTheSameTimeBudget() {
        val reveal = WordReveal()
        val sentence = (1..100).joinToString(" ") { "word$it" }
        reveal.target(1, sentence, 0)
        val finished = reveal.frame(320)
        assertEquals(sentence, finished.visibleText)
        assertFalse(finished.animating)
        reveal.target(null, "", 400)
        assertEquals("", reveal.frame(400).visibleText)
    }
}
