package com.opensub.app.core

import kotlin.math.abs

private const val CLAUSE_MARKS = "，、；：。！？"
/** A comma this far into a unit closes it; sooner, as in "So,", there is too little to stand alone. */
private const val FEWEST_UNIT_WORDS = 3
/** From here a unit closes before a word that opens a new phrase. */
private const val PHRASE_UNIT_WORDS = 8
/** The longest unit: about one line of a film subtitle, in both languages. */
private const val MOST_UNIT_WORDS = 12
/** A unit at the limit runs on this far rather than end on a word like "of". */
private const val LAST_RESORT_UNIT_WORDS = 15
/**
 * The Chinese kept as background: enough to hold "you", terms and names steady. It grows and is cut back now and then rather
 * than sliding, because the translator only works through what changed, and growing text changes only at its end.
 */
private const val MOST_BACKGROUND_CHARS = 90
private const val KEPT_BACKGROUND_CHARS = 30
/** A run of this many characters from the last subtitle means the model translated the background again. */
private const val REPEATED_CHARS = 5
/** "of" is not here: "a new source / of power" reads as two things. */
private val PHRASE_OPENERS = "and or but so because when while if that which who where to in on at for with from by as than until unless although though".split(' ').toSet()
/** Words that make a stretch a statement, so the Chinese has a verb to build on. Regular past forms and contractions are caught by their endings. */
private val VERBS = ("am is are was were be been being have has had do does did will would shall should can could may might must " +
    "go goes went make makes made know knows knew think thinks thought see sees saw want wants need needs say says said take takes took " +
    "come comes came give gives gave get gets got find finds found tell tells told feel feels felt become becomes became show shows " +
    "keep keeps kept let lets put puts mean means meant leave leaves left seem seems stand stands stood bring brings brought hear hears heard").split(' ').toSet() +
    // Without these a long stretch had no verb and waited for the sentence to end: "and now leads an iconic American business
    // driving change at the heart of global technology" put its Chinese up 9 seconds after the video's own subtitle.
    ("lead drive create build use change help start run work try ask call move live believe happen provide allow grow offer remember " +
        "buy serve send expect stay reach remain require sell decide pull break win learn read spend speak write turn begin sit play hold " +
        "include continue follow stop open walk lose pay meet understand look love like hope talk tell simulate design enable present").split(' ').flatMap(::verbForms)
private val SUBJECTS = setOf("i", "you", "we", "they", "he", "she", "it")
/**
 * Where a subtitle cannot end: a small word that leads on, a subject with its verb still to come, or a word like "very" or
 * "possibly" with what it qualifies still to come. Real talks paused after "although when I", "someone who's very" and "This
 * can't possibly", and the pieces read as nonsense (这不可能 / 在其他任何地方发生).
 */
private val UNFINISHED_ENDINGS = LINKING_WORDS + setOf("i", "we", "they", "he", "she", "very", "possibly", "only", "just", "even", "also", "never")
/**
 * Once the speaker has stopped, these end what was said: "with my shoes on", "carpets before". Waiting for the next sentence
 * put such endings 2.5 to 3.5 s behind the voice in a real interview, and with them the next sentence, which waits for the
 * last one's Chinese. Mid-phrase a unit still never ends on them, nor on "at" or "is" even at a stop ("we were standing at").
 */
private val ENDINGS_AFTER_STOP = setOf("on", "in", "before", "after", "about", "over", "through", "since")

/**
 * Speech without its stutters, for the model: a run of one to three words said twice over ("I think I think", "not a not a")
 * once. The screen keeps the words as said. Numbers are left alone, since "nine one one" is not a stutter.
 */
internal fun withoutRepeats(english: String): String {
    val words = english.split(' ').filter { it.isNotEmpty() }.toMutableList()
    fun plain(word: String) = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
    fun number(word: String) = plain(word).let { it in NUMBER_WORDS || it.any(Char::isDigit) }
    var at = 0
    while (at < words.size) {
        val size = (3 downTo 1).firstOrNull { size ->
            at + 2 * size <= words.size && (0 until size).all { plain(words[at + it]) == plain(words[at + size + it]) && !number(words[at + it]) }
        }
        if (size == null) at++ else repeat(size) { words.removeAt(at) }
    }
    return words.joinToString(" ")
}

private val HESITATIONS = setOf("um", "uh", "er", "erm", "hmm")

/** After these, "you know" is the question itself: "Do you know?", "How do you know?", "if you know, tell me". */
private val ASKS_YOU = setOf("do", "does", "did", "don't", "didn't", "how", "what", "why", "where", "when", "who", "whether", "if",
    "can", "could", "would", "will", "should", "as", "that", "because", "since", "unless")

/**
 * Speech without the fillers a fansub leaves out, for the model: "you know" and "like" set off by commas, "I mean," opening
 * the subtitle, um or uh, and "yeah," inside a sentence ("So yeah, today yeah, I think" came out as 所以，是的，今天，是的).
 * A subtitle that starts "Yeah," is answering, and keeps it. Translated, they came out as 你知道的 and 你懂的 in nearly every sentence of a fast talk, and cost
 * the tablet time. "you know that…" and "it's like a dream" still mean something and stay; so does the screen's English.
 */
internal fun withoutFillers(english: String): String {
    val words = english.split(' ').filter { it.isNotEmpty() }.toMutableList()
    fun plain(at: Int) = words[at].lowercase().filter { it.isLetter() || it == '\'' }
    fun opens(at: Int) = at == 0 || words[at - 1].last() in ",;:" || plain(at - 1) in JOINERS
    fun closes(at: Int) = words[at].last() in ",?" || at == words.lastIndex
    var at = 0
    while (at < words.size) {
        val size = when {
            plain(at) in HESITATIONS -> 1
            plain(at) == "you" && at + 1 < words.size && plain(at + 1) == "know" &&
                (words[at + 1].last() == ',' && (at == 0 || plain(at - 1) !in ASKS_YOU) || opens(at) && closes(at + 1)) -> 2
            plain(at) == "like" && words[at].last() == ',' && opens(at) -> 1
            plain(at) in setOf("yeah", "yep") && at > 0 && words[at].last() == ',' && opens(at) -> 1
            plain(at) == "i" && at == 0 && at + 1 < words.size && words[at + 1].lowercase() == "mean," -> 2
            else -> 0
        }
        if (size == 0) { at++; continue }
        // A filler that ends the subtitle gives its mark to the word before: "for me, you know?" asks as "for me?".
        val mark = words[at + size - 1].last().takeIf { it in "?!" }
        repeat(size) { words.removeAt(at) }
        if (at > 0 && at == words.size) words[at - 1] = words[at - 1].trimEnd(',', ';', ':') + (mark ?: "")
    }
    return words.joinToString(" ")
}

/** These open a phrase that can stand alone with no verb: "last month", "in the summer". */
private val STANDALONE_OPENERS = "in on at for from during after before since last next by with through across".split(' ').toSet()
private val JOINERS = setOf("and", "or", "but", "so", "then")

/**
 * Whether a subtitle can end here. Not everything needs a verb: "last month" reads fine alone. What cannot end is what leaves the
 * reader hanging: a small word that leads on, or a subject or condition still waiting for its verb, as in "only if the United States".
 */
private fun saysSomething(words: List<String>, stopped: Boolean = false): Boolean {
    val plain = words.map { word -> word.lowercase().filter { it.isLetter() || it == '\'' } }
    if (plain.last() in UNFINISHED_ENDINGS && !(stopped && plain.last() in ENDINGS_AFTER_STOP)) return false
    if (plain.first { it !in JOINERS || it == plain.last() } in STANDALONE_OPENERS) return true
    return plain.indices.any { at ->
        val word = plain[at]
        word in VERBS || (word.endsWith("ed") && words[at].first().isLowerCase()) || "'" in word || (word in SUBJECTS && at + 1 < plain.size)
    }
}

private val INSTRUCTIONS = Regex("只应?能?输出|翻译结果|添加任何|任何(解释|注释|说明)|^(请)?注意[，：:]|背景信息|Background Information")
/** Subtitles of the sentence a continuation reads before the new one: enough to carry the meaning across the cut, and short to read. */
private const val CONTEXT_UNITS = 2
/** A particle that ends the subtitle before, at the start of a continuation. */
private val FINISHING_PARTICLE = Regex("^[的了吗呢啊吧呀嘛]{1,2}(?=[，。！？、])")

/**
 * A subtitle's English without the full stop at its end, the same on every path so a guess matches the real request: the model
 * adds one wherever audio ends, and the Chinese drops it anyway. Questions and exclamations keep their mark.
 */
private fun withoutFullStop(words: List<String>) = (words.dropLast(1) + words.last().trimEnd('.')).joinToString(" ")

/** 当 opening a clause that ends in 时, a habit carried over from English "when". 当然, 当时 and the like are other words. */
private val WHEN_CLAUSE = Regex("(^|[$CLAUSE_MARKS])当(?![时然地前今初年场中做成作])(?=[^$CLAUSE_MARKS]{1,30}时候?，)")

/** Translated Chinese the way people say it: speech is not formal enough for 您, and a clause before 时 needs no 当. */
fun readable(chinese: String) = chinese.replace('您', '你').replace(WHEN_CLAUSE, "$1")

/** Film subtitles leave out a full stop at the end and show a pause inside a line as a space. Questions and exclamations keep their marks. */
fun subtitlePunctuation(chinese: String) = chinese.trimEnd('。', '，').replace('，', '　').replace('。', '　').replace('；', '　').replace('、', '　')

/** Text too long for one row of [rowChars], broken in two near the middle, after a clause where there is one. Text already in rows stays as it is. */
fun balancedRows(text: String, rowChars: Int): String {
    if (text.length <= rowChars || '\n' in text) return text
    val middle = text.length / 2
    val reach = text.length / 6
    val cut = (middle - reach..middle + reach).filter { text[it - 1] in CLAUSE_MARKS }.minByOrNull { abs(it - middle) } ?: middle
    return text.take(cut) + "\n" + text.drop(cut)
}

/**
 * Turns speech into subtitles the way a fansub does: a sentence is cut into short units, and each is shown as one English line
 * with its own Chinese, whole, once. Nothing half-translated reaches the screen, so nothing on it ever changes.
 *
 * Each unit is translated alone with the Chinese shown so far as background, so a video's terms and names stay the same from
 * one subtitle to the next. Tests on real speech: units read cleanly where growing drafts guessed (您不应该与每一位老师都进不去) or
 * rewrote the screen. English background translated itself into the reply in 4 units of 24; Chinese never did.
 * A whole sentence translated once more, from its second reading, goes to the history and the export.
 */
class ChineseConductor(private val delegate: Translator) {
    /** One subtitle: [english] with its [chinese]. */
    class Shown(val english: String, val chinese: String)

    private var sentenceId = 0L
    private var closedWords = 0
    private var lastChinese = ""
    private var background = ""
    private val sentenceUnits = ArrayList<Shown>()

    /** The subtitles of the current sentence put together: all the Chinese it has until its whole translation is done. */
    var sentenceChinese = ""
        private set

    /**
     * The units [cue] completes, in order. Usually none. Each goes to [each] as soon as it is translated: when a reading
     * closes two at once, the first no longer waits for the second's translation to reach the screen.
     */
    suspend fun units(cue: SubtitleCue, each: (Shown) -> Unit = {}): List<Shown> {
        if (cue.id != sentenceId) startSentence(cue.id)
        // Every word as heard: holding back the last few for the translator's sake costs a subtitle a second or more.
        val words = cue.english.split(' ').filter { it.isNotEmpty() }
        return buildList {
            while (true) {
                val size = unitSize(words.drop(closedWords))
                if (size == 0) break
                val shown = unit(words.subList(closedWords, closedWords + size).joinToString(" "), closedWords + size) ?: break
                each(shown)
                add(shown)
            }
        }
    }

    private fun unitSize(open: List<String>): Int {
        for (at in open.indices) {
            val size = at + 1
            val nextOpensPhrase = open.getOrNull(at + 1)?.lowercase()?.trimEnd(',', '.') in PHRASE_OPENERS
            val phraseEnds = size >= PHRASE_UNIT_WORDS && nextOpensPhrase && saysSomething(open.take(size))
            val full = size >= LAST_RESORT_UNIT_WORDS || (size >= MOST_UNIT_WORDS && open[at].lowercase().trimEnd(',', '.') !in UNFINISHED_ENDINGS)
            if ((size >= FEWEST_UNIT_WORDS && endsClause(open, at)) || phraseEnds || full) return size
        }
        return 0
    }

    /**
     * The comma in "Arlington, Virginia" only separates two names, so a name waits to see what comes after it. A comma the
     * recognizer put after a word that leads on ("I think that, we should") ends nothing either.
     */
    private fun endsClause(words: List<String>, at: Int): Boolean {
        val word = words[at]
        if (!word.endsWith(',') || word.lowercase().filter { it.isLetter() || it == '\'' } in UNFINISHED_ENDINGS) return false
        if (at == 0 || !word.first().isUpperCase()) return true
        return words.getOrNull(at + 1)?.first()?.isUpperCase() == false
    }

    /**
     * The speaker stopped, perhaps mid-thought: speakers pause after "and only if the United States", and a subtitle cut there
     * reads as nonsense, so what has no verb yet waits for the words after the pause. The model's mark here is not a real one.
     * What is left of a sentence shows however short ("to be there"): until 0.9.29 fewer than four words waited for the next
     * sentence, and so did that sentence, for the last one's Chinese.
     * The words are the second reading's, in [SubtitleCue.english]: a subtitle never changes once up, so it had better be right.
     */
    suspend fun atPause(cue: SubtitleCue): Shown? {
        if (cue.id != sentenceId) startSentence(cue.id)
        val heard = words(cue.sentenceEnglish)
        val open = secondReading(heard, words(cue.english))
        if (open.isEmpty() || !saysSomething(open, stopped = true)) return null
        return unit(withoutFullStop(open), heard.size)
    }

    /**
     * Translates ahead the next subtitle, from words heard but not settled yet: the unit they would close, or all of them if
     * the model hears the sentence end there. When the words settle as they were, the translation is already in the cache, and
     * the subtitle skips the translator's half second (A/B on real dialogue: a third of subtitles, 0.2 to 0.35 s sooner).
     * Nothing here changes what has been shown.
     */
    suspend fun guess(cue: SubtitleCue) {
        val shown = if (cue.id == sentenceId) closedWords else 0
        val heard = words(cue.english)
        if (shown >= heard.size) return
        val open = heard.drop(shown)
        val size = unitSize(open)
        val english = when {
            size > 0 -> open.take(size).joinToString(" ")
            open.last().last() in ".?!" && saysSomething(open, stopped = true) -> withoutFullStop(open)
            else -> return
        }
        if (cue.id == sentenceId) translateUnit(english) else fresh(english, background)
    }

    /** All that has been said and not shown, now that the sentence has ended, as its second reading heard it. */
    suspend fun rest(cue: SubtitleCue): Shown? {
        if (cue.id != sentenceId) startSentence(cue.id)
        val heard = words(cue.sentenceEnglish.ifEmpty { cue.english })
        val open = secondReading(heard, words(cue.english))
        return if (open.isEmpty()) null else unit(withoutFullStop(open), heard.size)
    }

    private fun words(text: String) = text.split(' ').filter { it.isNotEmpty() }

    /**
     * The words of a second reading [read] that come after those already shown. Shown words are counted in the words as first
     * [heard], and the readings can differ in a word or two, so the two are matched where they agree: their longest run of
     * words in common, in order.
     */
    private fun secondReading(heard: List<String>, read: List<String>): List<String> {
        if (closedWords >= heard.size) return emptyList()
        if (closedWords == 0) return read
        fun plain(word: String) = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
        val same = { i: Int, j: Int -> plain(heard[i]) == plain(read[j]) }
        val common = Array(heard.size + 1) { IntArray(read.size + 1) }
        for (i in heard.indices.reversed()) for (j in read.indices.reversed())
            common[i][j] = if (same(i, j)) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
        // The last shown word both readings agree on, and the first unshown one.
        var lastShown = -1
        var cut = 0
        var firstUnshown = read.size
        var i = 0
        var j = 0
        while (i < heard.size && j < read.size) {
            when {
                same(i, j) -> {
                    if (i < closedWords) { lastShown = i; cut = j + 1 } else firstUnshown = minOf(firstUnshown, j)
                    i++
                    j++
                }
                common[i + 1][j] >= common[i][j + 1] -> i++
                else -> j++
            }
        }
        // Shown words heard differently the second time still count as shown.
        return read.drop(minOf(cut + closedWords - 1 - lastShown, firstUnshown))
    }

    /** No Chinese leaves the words open, for the next subtitle or the end of the sentence: real use lost "for me as I've been using". */
    private suspend fun unit(english: String, closeTo: Int): Shown? {
        val chinese = translateUnit(english).trimEnd('，', '。', '、', '；', '：')
        if (chinese.isEmpty()) return null
        closedWords = closeTo
        lastChinese = chinese
        sentenceUnits += Shown(english, chinese)
        sentenceChinese += if (sentenceChinese.isEmpty()) chinese else "，$chinese"
        background = "$background$chinese，".let { if (it.length > MOST_BACKGROUND_CHARS) it.takeLast(KEPT_BACKGROUND_CHARS) else it }
        return Shown(english, chinese)
    }

    /** Always answers, so a caption in the history gets its Chinese however busy the translator has been. */
    suspend fun whole(cue: SubtitleCue) = Shown(cue.english, fresh(cue.english, ""))

    /** The speech that follows has nothing to do with what came before. */
    fun startOver() {
        startSentence(0)
        lastChinese = ""
        background = ""
    }

    private fun startSentence(id: Long) {
        sentenceId = id
        closedWords = 0
        sentenceChinese = ""
        sentenceUnits.clear()
    }

    /**
     * A subtitle after a cut in its sentence is asked as the continuation of the subtitles before it: the model reads their
     * English with this one, and writes on from their Chinese. Alone it lost the sense across the cut ("I can multiply /
     * multiple numbers together" came out as 相加, adding), and given the Chinese so far as background it repeated that
     * background in 8 of 45 such subtitles from a real lecture, which then had to be asked again; as a continuation, none.
     * The first subtitle of a sentence, and a continuation that comes back empty, are asked alone with the background.
     */
    private suspend fun translateUnit(english: String): String = continued(english) ?: fresh(english, background)

    private suspend fun continued(english: String): String? {
        if (!delegate.continues || sentenceUnits.isEmpty()) return null
        val context = sentenceUnits.takeLast(CONTEXT_UNITS)
        val shown = context.joinToString("，") { it.chinese }
        val reply = delegate.continueFrom(context.joinToString(" ") { withoutFillers(withoutRepeats(it.english)) } + " " + asked(english), shown)
        if (!reply.startsWith(shown)) return null
        // The model may first finish the subtitle before ("你知道" + "的，尤其是"), and that belongs to the screen already.
        val rest = reply.removePrefix(shown).replace(FINISHING_PARTICLE, "").trimStart('，', '。', '、', '；', '：', ' ')
        val chinese = clean(english, rest).trim().trimEnd('…')
        // Sometimes all it adds is the end of the subtitle before: "complained about it all the time" came back as 一样, finishing
        // "like a closed book". Chinese has at least a character for every two English words.
        val tooShort = chinese.length * 2 < words(english).size
        return chinese.takeIf { it.isNotEmpty() && it.length <= english.length && !tooShort && !repeatsLast(it) }
    }

    /** What the model is asked for [english]: without stutters or fillers, and a name called out moved to the front. */
    private fun asked(english: String) = vocativeFirst(withoutFillers(withoutRepeats(english)))

    /** Nothing to ask for fillers alone ("like, you know,"): no Chinese, so their words wait for the next subtitle. */
    private suspend fun fresh(english: String, before: String): String {
        val request = asked(english)
        if (request.none(Char::isLetterOrDigit)) return ""
        // A translator that ignores the background is asked plainly: a retry without it would be the same request again.
        val chinese = clean(english, if (before.isEmpty() || !delegate.readsBackground) delegate.translate(request) else translatedAfter(request, before)).trim().trimEnd('…')
        // The background now and then pulls the model off the sentence altogether ("[背景信息]…"); alone it still translates it.
        return if (chinese.isEmpty() && before.isNotEmpty()) clean(english, delegate.translate(request)).trim().trimEnd('…') else chinese
    }

    /** Now and then the model translates the background too, which shows as a reply longer than its English or repeating the last subtitle. */
    private suspend fun translatedAfter(english: String, before: String) =
        delegate.translateAfter(english, before).takeIf { it.length <= english.length && !repeatsLast(it) } ?: delegate.translate(english)

    private fun repeatsLast(chinese: String) = chinese.windowed(REPEATED_CHARS).any { it in lastChinese }

    /** The model's own instructions are never a translation, whatever the speech said. */
    private fun clean(english: String, chinese: String) = if (INSTRUCTIONS.containsMatchIn(chinese) && "note" !in english.lowercase()) "" else readable(simplified(chinese))
}
