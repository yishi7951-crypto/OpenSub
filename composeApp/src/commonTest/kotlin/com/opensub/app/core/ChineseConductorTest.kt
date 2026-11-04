package com.opensub.app.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChineseConductorTest {
    private class Scripted(vararg val replies: String) : Translator {
        var next = 0
        val asked = ArrayList<Pair<String, String>>()
        override suspend fun translate(english: String) = translateAfter(english, "")
        override suspend fun translateAfter(english: String, before: String): String {
            asked += english to before
            return replies[next++]
        }
        override fun close() {}
    }

    /** Writes on from what it is shown, the way Hy-MT2 does. */
    private class Continuing(vararg val replies: String) : Translator {
        var next = 0
        val continued = ArrayList<Pair<String, String>>()
        override val continues get() = true
        override suspend fun translate(english: String) = replies[next++]
        override suspend fun translateAfter(english: String, before: String) = replies[next++]
        override suspend fun continueFrom(english: String, shown: String): String {
            continued += english to shown
            return shown + replies[next++]
        }
        override fun close() {}
    }

    private fun spoken(english: String, id: Long = 1) = SubtitleCue(id, 0, 1000, english, 0, false, sentenceEnglish = english)
    private fun final(english: String, id: Long = 1) = SubtitleCue(id, 0, 1000, english, 0, true)

    @Test fun nothingIsShownWhileAUnitIsOpen() = runTest {
        val model = Scripted("x")
        assertTrue(ChineseConductor(model).units(spoken("One two three four five")).isEmpty())
        assertTrue(model.asked.isEmpty())
    }

    @Test fun aCommaClosesAUnit() = runTest {
        val unit = ChineseConductor(Scripted("除非你前往那些学校，")).units(spoken("Unless you show up, then")).single()
        assertEquals("Unless you show up,", unit.english)
        assertEquals("除非你前往那些学校", unit.chinese)
    }

    @Test fun anEarlyCommaIsTooLittleToStandAlone() = runTest {
        assertTrue(ChineseConductor(Scripted("x")).units(spoken("So, one two")).isEmpty())
    }

    @Test fun aCommaBetweenTwoNamesDoesNotCloseAUnit() = runTest {
        assertTrue(ChineseConductor(Scripted("x")).units(spoken("students in Arlington, Virginia today")).isEmpty())
    }

    @Test fun aLongUnitClosesBeforeANewPhrase() = runTest {
        val unit = ChineseConductor(Scripted("一二")).units(spoken("we went three four five six seven eight until nine")).single()
        assertEquals("we went three four five six seven eight", unit.english)
    }

    @Test fun aUnitNeverRunsPastItsLimit() = runTest {
        val unit = ChineseConductor(Scripted("一二")).units(spoken("a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13")).single()
        assertEquals(12, unit.english.split(' ').size)
    }

    @Test fun wordsNotYetSteadyStillCount() = runTest {
        val cue = spoken("Unless you show up, then").copy(sentenceEnglish = "Unless you")
        assertEquals("Unless you show up,", ChineseConductor(Scripted("一二")).units(cue).single().english)
    }

    @Test fun aFullUnitDoesNotEndOnASmallWord() = runTest {
        val unit = ChineseConductor(Scripted("一二")).units(spoken("a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 of a13 a14 a15")).single()
        assertEquals(13, unit.english.split(' ').size)
    }

    @Test fun theSubtitlesOfASentenceAddUp() = runTest {
        val conductor = ChineseConductor(Scripted("一二", "三四"))
        conductor.units(spoken("One two three,"))
        conductor.rest(final("One two three, four five."))
        assertEquals("一二，三四", conductor.sentenceChinese)
    }

    @Test fun aUnitIsTranslatedOnlyOnce() = runTest {
        val model = Scripted("一二")
        val conductor = ChineseConductor(model)
        conductor.units(spoken("One two three,"))
        assertTrue(conductor.units(spoken("One two three, four")).isEmpty())
        assertEquals(1, model.asked.size)
    }

    @Test fun theChineseSoFarIsBackgroundForTheNextUnit() = runTest {
        val model = Scripted("一二", "三四")
        val conductor = ChineseConductor(model)
        conductor.units(spoken("One two three,"))
        conductor.units(spoken("One two three, four five six,"))
        assertEquals("four five six," to "一二，", model.asked.last())
    }

    @Test fun aReplyThatRepeatsTheBackgroundIsThrownAway() = runTest {
        val long = "你或许可以成为一名出色的作家，甚至足以在报纸上撰写书籍或文章，但你可能直到写作时才会意识到这一点。"
        val conductor = ChineseConductor(Scripted("一二", long, "那篇论文"))
        conductor.units(spoken("One two three,"))
        assertEquals("那篇论文", conductor.units(spoken("One two three, that paper now,")).single().chinese)
    }

    @Test fun aReplyThatRepeatsTheLastSubtitleIsThrownAway() = runTest {
        val conductor = ChineseConductor(Scripted("我们才研发出了青霉素", "仅上周我们才研发出了青霉素和电视", "以及核能"))
        conductor.units(spoken("Only last week did we develop penicillin,"))
        assertEquals("以及核能", conductor.units(spoken("Only last week did we develop penicillin, and nuclear power now and television,")).single().chinese)
    }

    @Test fun twoUnitsCanCloseAtOnce() = runTest {
        val units = ChineseConductor(Scripted("一二", "三四")).units(spoken("One two three, four five six,"))
        assertEquals(listOf("一二", "三四"), units.map { it.chinese })
    }

    @Test fun theEndOfASentenceShowsWhatIsLeft() = runTest {
        val model = Scripted("一二", "三四")
        val conductor = ChineseConductor(model)
        conductor.units(spoken("One two three,"))
        assertEquals("four five", conductor.rest(final("One two three, four five."))?.english)
    }

    @Test fun theRestComesFromTheSecondReading() = runTest {
        val conductor = ChineseConductor(Scripted("一二", "三四"))
        conductor.units(spoken("One two three,"))
        val read = final("One too three, four five extra.").copy(sentenceEnglish = "One two three, four five.")
        assertEquals("four five extra", conductor.rest(read)?.english)
    }

    @Test fun aShownWordHeardDifferentlyTheSecondTimeIsNotShownAgain() = runTest {
        val conductor = ChineseConductor(Scripted("一二", "三四"))
        conductor.units(spoken("One two three, four"))
        val read = final("One two tree, four five.").copy(sentenceEnglish = "One two three, four five.")
        assertEquals("four five", conductor.rest(read)?.english)
    }

    @Test fun aPauseShowsTheSecondReading() = runTest {
        val read = spoken("I don't pull the trigger.").copy(english = "I didn't pull the trigger.")
        assertEquals("I didn't pull the trigger", ChineseConductor(Scripted("我没有扣动扳机")).atPause(read)?.english)
    }

    @Test fun aGuessAsksForWhatThePauseWillShow() = runTest {
        val model = Scripted("她很美", "她很美")
        val conductor = ChineseConductor(model)
        conductor.guess(spoken("She was beautiful."))
        conductor.atPause(spoken("She was beautiful."))
        assertEquals(listOf("She was beautiful", "She was beautiful"), model.asked.map { it.first })
    }

    @Test fun aGuessTranslatesTheUnitTheWordsSoFarWouldClose() = runTest {
        val model = Scripted("除非你来")
        ChineseConductor(model).guess(spoken("Unless you show up, then"))
        assertEquals("Unless you show up,", model.asked.single().first)
    }

    @Test fun aGuessWithNoSubtitleInItAsksNothing() = runTest {
        val model = Scripted("x")
        ChineseConductor(model).guess(spoken("Unless you show"))
        assertTrue(model.asked.isEmpty())
    }

    @Test fun aGuessShowsNothing() = runTest {
        val conductor = ChineseConductor(Scripted("她很美", "她很美"))
        conductor.guess(spoken("She was beautiful."))
        assertEquals("", conductor.sentenceChinese)
        assertEquals("She was beautiful", conductor.atPause(spoken("She was beautiful."))?.english)
    }

    @Test fun aSubtitleAfterACutContinuesTheOnesBefore() = runTest {
        val model = Continuing("我可以相乘", "，把多个数相乘")
        val conductor = ChineseConductor(model)
        conductor.atPause(spoken("I can multiply."))
        val unit = conductor.rest(final("I can multiply multiple numbers together."))
        assertEquals("I can multiply multiple numbers together" to "我可以相乘", model.continued.single())
        assertEquals("把多个数相乘", unit?.chinese)
    }

    @Test fun aContinuationDoesNotFinishTheSubtitleBefore() = runTest {
        val model = Continuing("我知道", "的，尤其是工程师")
        val conductor = ChineseConductor(model)
        conductor.atPause(spoken("I know."))
        assertEquals("尤其是工程师", conductor.rest(final("I know, especially engineers."))?.chinese)
    }

    @Test fun wordsWithNoChineseStayForTheEndOfTheSentence() = runTest {
        val conductor = ChineseConductor(Scripted("一二", "", "", "三四五六"))
        conductor.units(spoken("One two three, four"))
        assertNull(conductor.atPause(spoken("One two three, four is five here.")))
        assertEquals("four is five here, six", conductor.rest(final("One two three, four is five here, six."))?.english)
    }

    @Test fun anEmptyContinuationIsAskedAlone() = runTest {
        val model = Continuing("我在一些视频里更年轻", "", "比现在")
        val conductor = ChineseConductor(model)
        conductor.atPause(spoken("I look younger in some videos."))
        assertEquals("比现在", conductor.rest(final("I look younger in some videos than I am now."))?.chinese)
    }

    @Test fun aContinuationThatOnlyEndsTheSubtitleBeforeIsAskedAlone() = runTest {
        val model = Continuing("就像一本看不透的书", "一样", "她总是抱怨这件事")
        val conductor = ChineseConductor(model)
        conductor.atPause(spoken("Like a closed book."))
        assertEquals("她总是抱怨这件事", conductor.rest(final("Like a closed book. Complained about it all the time."))?.chinese)
    }

    @Test fun aGuessAfterACutAsksTheSameContinuation() = runTest {
        val model = Continuing("我可以相乘", "，然后把数加起来", "，然后把数加起来")
        val conductor = ChineseConductor(model)
        conductor.atPause(spoken("I can multiply."))
        conductor.guess(spoken("I can multiply and then we add the numbers."))
        conductor.rest(final("I can multiply and then we add the numbers."))
        assertEquals(2, model.continued.size)
        assertEquals(1, model.continued.distinct().size)
    }

    @Test fun aShortSentenceShowsAtAPause() = runTest {
        assertEquals("She was beautiful", ChineseConductor(Scripted("她很美")).atPause(spoken("She was beautiful."))?.english)
    }

    @Test fun aShortPieceLeftOfASentenceWaitsOutAPause() = runTest {
        val conductor = ChineseConductor(Scripted("一二", "x"))
        conductor.units(spoken("One two three, four"))
        assertNull(conductor.atPause(spoken("One two three, four was.")))
    }

    @Test fun aPauseBeforeTheVerbWaits() = runTest {
        val model = Scripted("x")
        assertNull(ChineseConductor(model).atPause(spoken("and only if the United States.")))
        assertTrue(model.asked.isEmpty())
    }

    @Test fun aPhraseOfTimeOrPlaceCanStandAlone() = runTest {
        assertEquals("Last month in Paris", ChineseConductor(Scripted("上个月")).atPause(spoken("Last month in Paris."))?.english)
        assertEquals("and in the summer", ChineseConductor(Scripted("在夏天")).atPause(spoken("and in the summer."))?.english)
    }

    @Test fun aPauseRightAfterASubjectWaits() = runTest {
        assertNull(ChineseConductor(Scripted("x")).atPause(spoken("one of the things I've seen is that although when I")))
        assertNull(ChineseConductor(Scripted("x")).atPause(spoken("and be seen as someone who's very")))
        assertNull(ChineseConductor(Scripted("x")).atPause(spoken("This can't possibly")))
    }

    @Test fun aLongStretchWithACommonVerbClosesBeforeTheNextPhrase() = runTest {
        val unit = ChineseConductor(Scripted("如今领导着一家标志性的美国企业，推动着变革")).units(spoken("and now leads an iconic American business driving change at the heart")).single()
        assertEquals("and now leads an iconic American business driving change", unit.english)
    }

    @Test fun stuttersAreNotAskedOfTheModel() = runTest {
        val model = Scripted("我认为主要的主题")
        ChineseConductor(model).atPause(spoken("I think I think the big theme is speed"))
        assertEquals("I think the big theme is speed", model.asked.single().first)
    }

    @Test fun withoutRepeatsDropsRunsSaidTwice() {
        assertEquals("not someone not a tool", withoutRepeats("not someone not a not a tool"))
        assertEquals("like a pair", withoutRepeats("like a like a pair"))
        assertEquals("with AI.", withoutRepeats("with with AI."))
        assertEquals("call nine one one", withoutRepeats("call nine one one"))
        assertEquals("the big theme", withoutRepeats("the big theme"))
    }

    @Test fun eachUnitIsHandedOnBeforeTheNextIsTranslated() = runTest {
        val events = ArrayList<String>()
        val model = object : Translator {
            var next = 0
            override suspend fun translate(english: String) = "译$next".also { events += "ask $english"; next++ }
            override fun close() {}
        }
        ChineseConductor(model).units(spoken("One two three, four five six, seven")) { events += "show ${it.english}" }
        assertEquals(listOf("ask One two three,", "show One two three,", "ask four five six,", "show four five six,"), events)
    }

    @Test fun aTranslatorWithoutBackgroundIsAskedEachUnitOnce() = runTest {
        val asked = ArrayList<String>()
        val model = object : Translator {
            override suspend fun translate(english: String) = (if (asked.isEmpty()) "一二三四五" else "一二三四五六").also { asked += english }
            override val readsBackground get() = false
            override fun close() {}
        }
        val conductor = CachingTranslator(model).let(::ChineseConductor)
        conductor.units(spoken("One two three, four five six,"))
        assertEquals(listOf("One two three,", "four five six,"), asked)
    }

    @Test fun aCommaAfterAWordThatLeadsOnIsNoCut() = runTest {
        assertTrue(ChineseConductor(Scripted("x")).units(spoken("I think that, we should leave")).isEmpty())
        assertTrue(ChineseConductor(Scripted("x")).units(spoken("one of the, the best")).isEmpty())
    }

    @Test fun withoutFillersDropsWhatAFansubLeavesOut() {
        assertEquals("guessing, hallucinating", withoutFillers("guessing, hallucinating, you know,"))
        assertEquals("like a fresh perspective on", withoutFillers("you know, like a fresh perspective on"))
        assertEquals("keep the code base", withoutFillers("like, you know, keep the code base"))
        assertEquals("for me?", withoutFillers("for me, you know?"))
        assertEquals("doing good engineering and when you see agents just", withoutFillers("doing good engineering and when you see agents just you know,"))
        assertEquals("we should go", withoutFillers("I mean, um, we should go"))
        assertEquals("So today, I think the big theme", withoutFillers("So yeah, today, yeah, I think the big theme"))
        assertEquals("Yes", withoutFillers("Yes, yeah,"))
        assertEquals("Yeah, we're good", withoutFillers("Yeah, we're good"))
        assertEquals("", withoutFillers("like, you know,"))
        // Meant, not filler.
        assertEquals("you know that it works", withoutFillers("you know that it works"))
        assertEquals("it's like a dream", withoutFillers("it's like a dream"))
        assertEquals("what I mean, is this", withoutFillers("what I mean, is this"))
        assertEquals("Do you know?", withoutFillers("Do you know?"))
        assertEquals("How do you know?", withoutFillers("How do you know?"))
        assertEquals("if you know, tell me", withoutFillers("if you know, tell me"))
        assertEquals("did you know", withoutFillers("did you know"))
    }

    @Test fun fillersAloneAreNotAsked() = runTest {
        val translator = Scripted("x")
        assertNull(ChineseConductor(translator).atPause(spoken("like, you know.")))
        assertEquals(0, translator.asked.size)
    }

    @Test fun aSubjectStillWaitingForItsVerbWaits() = runTest {
        assertNull(ChineseConductor(Scripted("x")).atPause(spoken("and the steam engine.")))
    }

    @Test fun aScrapWaitsOutAPause() = runTest {
        assertNull(ChineseConductor(Scripted("x")).atPause(spoken("the next time.")))
    }

    @Test fun aPauseOnAWordThatLeadsOnWaits() = runTest {
        assertNull(ChineseConductor(Scripted("x")).atPause(spoken("we were standing at")))
    }

    @Test fun whatIsLeftOfASentenceShowsAtAStopHoweverShort() = runTest {
        val conductor = ChineseConductor(Scripted("我的父母认为这对我们不安全", "待在那里"))
        conductor.units(spoken("My parents thought it was unsafe for us,"))
        assertEquals("to be there", conductor.atPause(spoken("My parents thought it was unsafe for us, to be there."))?.english)
    }

    @Test fun aStopAfterAWordLikeOnEndsTheSubtitle() = runTest {
        assertEquals("I walked on my bed with my shoes on", ChineseConductor(Scripted("我穿着鞋在床上走")).atPause(spoken("I walked on my bed with my shoes on."))?.english)
        val conductor = ChineseConductor(Scripted("我的第一印象是我从未站在房子里", "铺着地毯"))
        conductor.units(spoken("My first impression was I've never stood in a house with"))
        assertEquals("with carpets before", conductor.atPause(spoken("My first impression was I've never stood in a house with carpets before."))?.english)
    }

    @Test fun aPauseAfterAStatementShowsIt() = runTest {
        assertEquals("Newton explored the meaning of gravity", ChineseConductor(Scripted("牛顿")).atPause(spoken("Newton explored the meaning of gravity."))?.english)
    }

    @Test fun whatWaitedJoinsTheWordsAfterThePause() = runTest {
        val conductor = ChineseConductor(Scripted("蒸汽机提供了动力"))
        assertNull(conductor.atPause(spoken("the steam engine.")))
        assertEquals("the steam engine provided power", conductor.rest(final("the steam engine provided power."))?.english)
    }

    @Test fun aPhraseWithNoVerbRunsOnToThePhraseAfterIt() = runTest {
        val conductor = ChineseConductor(Scripted("x"))
        assertTrue(conductor.units(spoken("all the very great and very good old friends")).isEmpty())
    }

    @Test fun nothingLeftShowsNothing() = runTest {
        val conductor = ChineseConductor(Scripted("一二"))
        conductor.units(spoken("One two three,"))
        assertNull(conductor.rest(final("One two three,")))
    }

    @Test fun theBackgroundCarriesIntoTheNextSentence() = runTest {
        val model = Scripted("一二", "三四")
        val conductor = ChineseConductor(model)
        conductor.units(spoken("One two three,"))
        conductor.units(spoken("Four five six,", id = 2))
        assertEquals("Four five six," to "一二，", model.asked.last())
    }

    @Test fun theBackgroundKeepsOnlyTheLatestChinese() = runTest {
        val model = Scripted("甲".repeat(50), "乙".repeat(50), "丙丁")
        val conductor = ChineseConductor(model)
        val long = "wordwordwordwordone wordwordwordwordtwo wordwordwordwordthree,"
        conductor.units(spoken(long, id = 1))
        conductor.units(spoken(long, id = 2))
        conductor.units(spoken("Seven eight nine,", id = 3))
        assertEquals(30, model.asked.last().second.length)
        assertTrue(model.asked.last().second.endsWith("乙，"))
    }

    @Test fun theHistoryOwesNothingToTheUnits() = runTest {
        val model = Scripted("蒸汽机，", "蒸汽机提供了新的动力。")
        val conductor = ChineseConductor(model)
        conductor.units(spoken("The steam engine,"))
        assertEquals("蒸汽机提供了新的动力。", conductor.whole(final("The steam engine provided a new source of power.")).chinese)
        assertEquals("", model.asked.last().second)
    }

    @Test fun afterASeekTheFirstUnitHasNoBackground() = runTest {
        val model = Scripted("一二", "三四")
        val conductor = ChineseConductor(model)
        conductor.units(spoken("One two three,"))
        conductor.startOver()
        conductor.units(spoken("Four five six,"))
        assertEquals("", model.asked.last().second)
    }

    @Test fun theModelsInstructionsNeverReachTheScreen() = runTest {
        assertTrue(ChineseConductor(Scripted("请注意，你只能输出翻译结果，不能添加任何解释。")).units(spoken("the whole of,")).isEmpty())
    }

    @Test fun aSentenceThatIsOnlyInstructionsIsEmpty() = runTest {
        assertEquals("", ChineseConductor(Scripted("注意：您只应输出翻译结果，不要添加任何注释。")).whole(final("Not.")).chinese)
    }

    @Test fun aRealNoteIsKept() = runTest {
        assertEquals("注意，门开着。", ChineseConductor(Scripted("注意，门开着。")).whole(final("Note the door is open.")).chinese)
    }

    @Test fun traditionalCharactersNeverReachTheScreen() = runTest {
        assertEquals("放弃了自己，也放弃了国家。", ChineseConductor(Scripted("放棄了自己，也放棄了國家。")).whole(final("You quit.")).chinese)
        assertEquals("抵达金星后来", simplified("抵達金星後來"))
    }

    @Test fun wordsSimplifiedTextAlsoUsesAreLeftAlone() {
        assertEquals("著名的乾坤", simplified("著名的乾坤"))
    }

    @Test fun speechIsNotAddressedAs您() {
        assertEquals("你不应该", readable("您不应该"))
    }

    @Test fun aClauseBefore时Drops当() {
        assertEquals("你感觉不舒服时，应该待在家里。", readable("当你感觉不舒服时，应该待在家里。"))
        assertEquals("如果下雨，总统走进房间的时候，学生站了起来。", readable("如果下雨，当总统走进房间的时候，学生站了起来。"))
    }

    @Test fun wordsThatStartWith当AreKept() {
        assertEquals("当然，这是一个神话。", readable("当然，这是一个神话。"))
        assertEquals("当时我在家时，他来了。", readable("当时我在家时，他来了。"))
    }

    @Test fun subtitlesLikeFilmsDropMarksButKeepQuestions() {
        assertEquals("去发展新的能源技术　来保护环境", subtitlePunctuation("去发展新的能源技术，来保护环境。"))
        assertEquals("真的吗？", subtitlePunctuation("真的吗？"))
    }

    @Test fun aShortLineStaysOneRow() {
        assertEquals("一二三四", balancedRows("一二三四", 4))
    }

    @Test fun aLongLineBreaksAtTheClauseNearestTheMiddle() {
        assertEquals("一二三四，\n五六七八九十", balancedRows("一二三四，五六七八九十", 8))
    }

    @Test fun aLongLineWithNoClauseBreaksInTheMiddle() {
        assertEquals("一二三四\n五六七八", balancedRows("一二三四五六七八", 6))
    }
}
