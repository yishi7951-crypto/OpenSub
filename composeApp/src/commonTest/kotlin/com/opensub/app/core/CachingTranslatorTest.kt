package com.opensub.app.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CachingTranslatorTest {
    private class Counting : Translator {
        val calls = ArrayList<String>()
        override suspend fun translate(english: String): String { calls += english; return "译 $english" }
        override fun close() {}
    }

    @Test fun repeatedSentencesSkipTheModelIgnoringCase() = runTest {
        val model = Counting()
        val cached = CachingTranslator(model)
        assertEquals("译 Thank you.", cached.translate("Thank you."))
        assertEquals("译 Thank you.", cached.translate(" thank you. "))
        assertEquals(1, model.calls.size)
    }

    @Test fun aTranslationWithBackgroundIsCachedForThatBackground() = runTest {
        val model = Counting()
        val cached = CachingTranslator(model)
        cached.translateAfter("She was beautiful", "我爱她，")
        cached.translateAfter("She was beautiful", "我爱她，")
        cached.translateAfter("She was beautiful", "别的，")
        assertEquals(2, model.calls.size)
    }

    @Test fun theOldestEntryLeavesWhenFull() = runTest {
        val model = Counting()
        val cached = CachingTranslator(model, capacity = 2)
        for (text in listOf("a", "b", "c", "a")) cached.translate(text)
        assertEquals(listOf("a", "b", "c", "a"), model.calls)
        cached.translate("c")
        assertEquals(4, model.calls.size)
    }

    @Test fun loopSourceRepeatsTheClipWithAContinuousClock() = runTest {
        val frames = PacedLoopSource(FloatArray(500) { it.toFloat() }, 60).frames().toList()
        assertEquals(listOf(0L, 320L, 640L), frames.map { it.startSample })
        assertEquals(499f, frames[1].samples[179])
        assertEquals(0f, frames[1].samples[180])
    }
}
