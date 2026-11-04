package com.opensub.app.core

import kotlin.test.*

class RollUpTest {
    @Test fun aShortLineIsOneRow() {
        assertEquals("one two", filledRows("one two", 7))
    }

    @Test fun theFirstRowFillsBeforeASecondStarts() {
        assertEquals("one two\nthree four five", filledRows("one two three four five", 9))
    }

    @Test fun rowsKeepTheLengthOfTheText() {
        assertEquals(23, filledRows("one two three four five", 9).length)
    }

    @Test fun shortTextTakesOneRow() {
        assertEquals("aa bb", RollUp({ 10 }, " ").put(1, "aa bb"))
    }

    @Test fun wordsStayOnTheirRowAsTheSentenceGrows() {
        val rows = RollUp({ 10 }, " ")
        assertEquals("aa bb cc\ndd", rows.put(1, "aa bb cc dd"))
        assertEquals("aa bb cc\ndd ee", rows.put(1, "aa bb cc dd ee"))
    }

    @Test fun aThirdRowPushesTheFirstOneOut() {
        assertEquals("dd ee ff\ngg", RollUp({ 10 }, " ").put(1, "aa bb cc dd ee ff gg"))
    }

    @Test fun theNextSentenceCarriesOnInTheSameRow() {
        val rows = RollUp({ 10 }, " ")
        rows.put(1, "aa bb.")
        assertEquals("aa bb. cc", rows.put(2, "cc"))
    }

    @Test fun aSecondReadingReplacesItsSentence() {
        val rows = RollUp({ 30 }, " ")
        rows.put(1, "they sold minds")
        rows.put(2, "then")
        assertEquals("they sowed mines. then", rows.put(1, "they sowed mines."))
    }

    @Test fun aSentenceThatRolledAwayStaysAway() {
        val rows = RollUp({ 10 }, " ")
        rows.put(1, "aa bb cc")
        rows.put(2, "dd ee ff gg hh ii")
        assertEquals("dd ee ff\ngg hh ii", rows.put(1, "aa bb cc."))
    }

    @Test fun aChineseSubtitleThatDoesNotFitStartsTheNextRow() {
        val rows = RollUp({ 6 }, "　")
        rows.put(1, "一二三四")
        assertEquals("一二三四\n五六七", rows.put(2, "五六七"))
    }

    @Test fun aLongSubtitleKeepsItsStartWhenTheOneBeforeLeaves() {
        val rows = RollUp({ 8 }, "　")
        rows.put(1, "一二三")
        assertEquals("四五 六七八九十\n甲乙丙丁", rows.put(2, "四五 六七八九十 甲乙丙丁"))
    }

    @Test fun chineseSubtitlesShareARowWithASpace() {
        val rows = RollUp({ 8 }, "　")
        rows.put(1, "一二三")
        assertEquals("一二三　四五", rows.put(2, "四五"))
    }

    @Test fun onlyAPhraseTooLongForARowIsCutInside() {
        assertEquals("一二三四\n五六", RollUp({ 4 }, "　").put(1, "一二三四五六"))
    }

    @Test fun aLatinWordInChineseStaysWhole() {
        assertEquals("Babel\n插件",RollUp({ 5 }, "　").put(1, "安装 Babel 插件"))
    }

    @Test fun clearingStartsAfresh() {
        val rows = RollUp({ 10 }, " ")
        rows.put(1, "aa bb cc dd ee ff gg")
        rows.clear()
        assertEquals("xx", rows.put(1, "xx"))
    }
}
