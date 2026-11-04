package com.opensub.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SrtExportTest {
    private val translated = SubtitleCue(1, 3_725_040, 3_727_500, "Hello.", 0, chinese = "你好。")
    private val pending = SubtitleCue(2, 3_728_000, 3_729_000, "Still waiting.", 0)

    @Test fun bilingualFollowsTheChosenOrderAndUsesSrtTimes() {
        val text = subtitlesToSrt(listOf(translated), SubtitleStyle(chineseFirst = true))
        assertEquals("﻿1\r\n01:02:05,040 --> 01:02:07,500\r\n你好。\r\nHello.\r\n\r\n", text)
    }

    @Test fun singleLanguageModesKeepOneLine() {
        assertTrue("你好。" !in subtitlesToSrt(listOf(translated), SubtitleStyle(mode = SubtitleMode.English)))
        assertTrue("Hello." !in subtitlesToSrt(listOf(translated), SubtitleStyle(mode = SubtitleMode.Chinese)))
    }

    @Test fun untranslatedSentencesFallBackToEnglishEvenInChineseMode() {
        assertTrue("Still waiting." in subtitlesToSrt(listOf(pending), SubtitleStyle(mode = SubtitleMode.Chinese)))
    }

    @Test fun bilingualReadingFormatsPutEachLanguageInItsOwnPart() {
        val text = subtitlesToText(listOf(translated, pending), SubtitleStyle(chineseFirst = false))
        assertEquals("\uFEFF中文\r\n你好。\r\n\r\nEnglish\r\nHello.\r\nStill waiting.\r\n", text)
    }

    @Test fun singleLanguageReadingFormatsHaveNoHeadings() {
        assertEquals("\uFEFF你好。\r\n", subtitlesToText(listOf(translated), SubtitleStyle(mode = SubtitleMode.Chinese)))
        assertEquals("# OpenSub 字幕\n\nHello.\n", subtitlesToMarkdown(listOf(translated), SubtitleStyle(mode = SubtitleMode.English)))
    }

    @Test fun numberingFollowsTheFileNotCaptionIds() {
        val text = subtitlesToSrt(listOf(translated, pending), SubtitleStyle())
        assertTrue("\r\n\r\n2\r\n01:02:08,000" in text)
    }
}
