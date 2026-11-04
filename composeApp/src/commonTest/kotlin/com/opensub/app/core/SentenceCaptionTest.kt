package com.opensub.app.core

import kotlin.test.*

class SentenceCaptionTest {
    private fun caption(readsChinese: Boolean = true) = SentenceCaption({ 20 }, { 10 }, { readsChinese })

    @Test fun aSentenceShowsWithItsOwnChinese() {
        val caption = caption()
        caption.english(1, "She was", 0)
        caption.english(1, "She was beautiful", 300)
        assertEquals(SentenceCaption.Shown(1, "She was beautiful", "她很漂亮"), caption.chinese(1, 1, "她很漂亮", 600))
    }

    @Test fun theNextSentenceWaitsWhileTheChineseIsRead() {
        val caption = caption()
        caption.english(1, "She was beautiful.", 0)
        caption.chinese(1, 1, "她很漂亮", 500)
        caption.finished(1, 600)
        assertEquals(1, caption.english(2, "We", 700).sentence)
        assertEquals(800, caption.waitMs(700))
        assertEquals(1, caption.advance(1400).sentence)
        assertEquals(SentenceCaption.Shown(2, "We", ""), caption.advance(1500))
    }

    @Test fun theNextSentenceWaitsForTheLastPieceOfChinese() {
        val caption = caption()
        caption.english(1, "I just did not assure it all.", 0)
        caption.chinese(1, 1, "我只是没有做到", 100)
        assertEquals(1, caption.english(2, "I killed her", 2000).sentence)
        caption.chinese(1, 2, "所有", 2300)
        assertEquals("我只是没有做到　所有", caption.shown.chinese)
        caption.finished(1, 2300)
        assertEquals(2, caption.advance(3000).sentence)
    }

    @Test fun theEnglishWaitsNoLongerThanASecond() {
        val caption = caption()
        caption.english(1, "Need all of you to take this seriously.", 0)
        caption.english(2, "So", 100)
        assertEquals(1, caption.advance(1000).sentence)
        assertEquals(SentenceCaption.Shown(2, "So", ""), caption.advance(1100))
    }

    @Test fun chineseThatArrivesAfterItsSentenceLeftIsDropped() {
        val caption = caption()
        caption.english(1, "Bad husband maybe.", 0)
        caption.english(2, "Feel bad", 100)
        caption.advance(1100)
        assertEquals(SentenceCaption.Shown(2, "Feel bad", ""), caption.chinese(1, 1, "糟糕的丈夫", 1200))
    }

    @Test fun chineseOfTheWaitingSentenceComesWithIt() {
        val caption = caption()
        caption.english(1, "No, I didn't.", 0)
        caption.english(2, "Somebody else did", 100)
        caption.chinese(2, 2, "是别人干的", 500)
        assertEquals(SentenceCaption.Shown(2, "Somebody else did", "是别人干的"), caption.advance(1100))
    }

    @Test fun withoutChineseOnScreenTheNextSentenceComesAtOnce() {
        val caption = caption(readsChinese = false)
        caption.english(1, "She was beautiful.", 0)
        caption.chinese(1, 1, "她很漂亮", 500)
        assertEquals(2, caption.english(2, "We", 600).sentence)
    }

    @Test fun aLongSentenceRollsWithinItself() {
        val caption = caption()
        caption.english(1, "aa bb cc dd ee ff gg hh ii jj kk", 0)
        assertEquals("hh ii jj kk ll mm nn\noo pp qq", caption.english(1, "aa bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp qq", 10).english)
    }

    @Test fun aSecondReadingReplacesTheShownEnglish() {
        val caption = caption()
        caption.english(1, "We I loved her", 0)
        assertEquals("We all loved her.", caption.english(1, "We all loved her.", 500).english)
    }
}
