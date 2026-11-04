package com.opensub.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptionPagerTest {
    private fun preview(id: Long, text: String) = SubtitleCue(id, 0, 0, text, 0, isFinal = false)
    private fun final(id: Long, text: String) = SubtitleCue(id, 0, 0, text, 0)

    @Test fun aLineWaitsUntilEnoughWordsHaveSettled() {
        val pager = CaptionPager()
        assertNull(pager.accept(preview(1, "one two")))
        assertEquals("one two three", pager.accept(preview(1, "one two three"))?.english)
    }

    @Test fun aPlacedLineNeverChangesAsTheSentenceGrows() {
        val pager = CaptionPager(maxLineChars = 24)
        val first = pager.accept(preview(1, "one two three four five"))
        assertNull(pager.accept(preview(1, "one two three four five six seven")))
        val second = pager.accept(preview(1, "one two three four five six seven eight nine ten"))
        assertEquals("one two three four five\nsix seven eight nine ten", second?.english)
        assertEquals(first?.id, second?.id)
    }

    @Test fun aFinalPlacesWhateverIsLeftEvenIfShort() {
        val pager = CaptionPager()
        pager.accept(preview(1, "one two three four five"))
        assertEquals("One two three four five\nsix.", pager.accept(final(1, "One two three four five six."))?.english)
    }

    @Test fun aCutDisplaysItsTailWithoutWaitingForTheNextPiece() {
        val pager = CaptionPager()
        assertEquals("the whole root", pager.accept(final(1, "the whole root"))?.english)
        assertEquals("the whole root\nand core of it", pager.accept(final(2, "and core of it"))?.english)
    }

    @Test fun aFullPageGivesWayToANewOne() {
        val pager = CaptionPager()
        val first = pager.accept(final(1, "first line."))
        pager.accept(final(2, "second line."))
        val third = pager.accept(final(3, "third line."))
        assertEquals("third line.", third?.english)
        assertEquals(first!!.id + 1, third!!.id)
    }

    @Test fun aFinalCorrectsTheVisibleWordsWithoutChangingThePage() {
        val pager = CaptionPager()
        val first = pager.accept(preview(1, "one two three four five"))
        val corrected = pager.accept(final(1, "one two tree four five."))
        assertEquals("one two tree four five.", corrected?.english)
        assertEquals(first?.id, corrected?.id)
        val longer = pager.accept(final(1, "one two tree four five six."))
        assertEquals("one two tree four five\nsix.", longer?.english)
        assertEquals(first?.id, longer?.id)
    }

    @Test fun aRevisionThatChangesTheWordCountStillCarriesOnFromTheRightWord() {
        val pager = CaptionPager()
        pager.accept(preview(1, "we can not go there"))
        assertEquals("we cannot go there\nnow.", pager.accept(final(1, "we cannot go there now."))?.english)
    }

    @Test fun aCorrectionCannotBringBackWordsFromAnEarlierPage() {
        val pager = CaptionPager(maxLineChars = 14)
        val text = "one two three four five six seven eight nine ten"
        val first = pager.accept(preview(1, text))
        val corrected = pager.accept(final(1, text.replace("one", "won") + "."))
        assertEquals(first?.id, corrected?.id)
        assertEquals("seven eight\nnine ten.", corrected?.english)
    }

    @Test fun clearingStartsAFreshPage() {
        val pager = CaptionPager()
        pager.accept(final(1, "old words."))
        pager.clear()
        assertEquals("new words.", pager.accept(final(2, "new words."))?.english)
    }

    @Test fun aNewSessionWithTheSameCueIdDoesNotLoseItsFirstWords() {
        val pager = CaptionPager()
        pager.accept(final(1, "old words from another session."))
        pager.reset()
        assertEquals("new first words", pager.accept(preview(1, "new first words"))?.english)
    }

    @Test fun longChineseShowsItsNewestPage() {
        assertEquals("短句", fitChinese("短句", 10))
        assertEquals("第三段内容", fitChinese("第一段内容很长，第二段内容，第三段内容", 8))
    }

    @Test fun chineseOnScreenStaysPutAsTheSentenceGrows() {
        assertEquals("第二段", fitChinese("第一段内容很长，第二段", 8))
        assertEquals("第二段内容", fitChinese("第一段内容很长，第二段内容", 8))
    }
}
