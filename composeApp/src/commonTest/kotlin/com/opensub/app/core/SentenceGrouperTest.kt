package com.opensub.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceGrouperTest {
    private fun cue(id: Long, startMs: Long, endMs: Long, english: String) = SubtitleCue(id, startMs, endMs, english, 0)

    @Test fun aPartWithoutAFullStopContinuesIntoTheNext() {
        val grouper = SentenceGrouper()
        grouper.add(cue(1, 0, 1000, "In a decade of hope and fear,"))
        val merged = grouper.add(cue(2, 1400, 3000, "We go on."))
        assertEquals(1, merged.id)
        assertEquals(3000, merged.endMs)
        assertEquals("In a decade of hope and fear, we go on.", merged.english)
    }

    @Test fun aLowerCaseStartRepairsAWronglyPlacedFullStop() {
        val grouper = SentenceGrouper()
        grouper.add(cue(1, 0, 1000, "A time span of half a."))
        assertEquals("A time span of half a century.", grouper.add(cue(2, 1100, 1500, "century.")).english)
    }

    @Test fun iKeepsItsCapital() {
        val grouper = SentenceGrouper()
        grouper.add(cue(1, 0, 1000, "And then,"))
        assertEquals("And then, I left.", grouper.add(cue(2, 1100, 1500, "I left.")).english)
    }

    @Test fun peekingDoesNotChangeTheSentence() {
        val grouper = SentenceGrouper()
        grouper.add(cue(1, 0, 1000, "And then,"))
        assertEquals("And then, it rained", grouper.peek(cue(2, 1100, 1500, "It rained")).english)
        assertEquals("And then, we left.", grouper.add(cue(2, 1100, 1500, "We left.")).english)
    }

    @Test fun foldingATranslationReplacesOnlyThePartsItCovers() {
        val parts = listOf(cue(1, 0, 1000, "a"), cue(2, 1100, 2000, "b"), cue(3, 2100, 3000, "c"))
        val folded = foldTranslation(parts, cue(1, 0, 2000, "a b").copy(chinese = "甲乙"))
        assertEquals(listOf("a b", "c"), folded.map { it.english })
    }

    @Test fun aFinishedSentenceStandsAlone() {
        val grouper = SentenceGrouper()
        grouper.add(cue(1, 0, 1000, "We choose to go."))
        assertEquals(2, grouper.add(cue(2, 1100, 2000, "It is hard.")).id)
    }

    @Test fun aLongSilenceEndsTheSentenceAnyway() {
        val grouper = SentenceGrouper()
        grouper.add(cue(1, 0, 1000, "and then"))
        assertEquals(2, grouper.add(cue(2, 3000, 4000, "something else.")).id)
    }

    @Test fun veryLongSentencesAreCutSoTranslationStaysFast() {
        val grouper = SentenceGrouper()
        var last = cue(0, 0, 0, "")
        for (part in 1L..6L) last = grouper.add(cue(part, part * 1000, part * 1000 + 500, "one two three four five six seven eight"))
        assertEquals(4, last.id)
    }
}
