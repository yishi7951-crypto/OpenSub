package com.opensub.app.core

/** What the default card shows, two lines of 32-sp Chinese; only used to count what visibly changes. */
private const val VISIBLE_CHINESE_CHARS = 60

/** Endurance-run counters, including how far Chinese trails the first word of each piece of speech. */
class SoakStats(private val nowMs: () -> Long) {
    private val waiting = LinkedHashSet<Long>()
    private val covered = HashSet<Long>()
    private val lags = ArrayList<Long>()
    private val settledLags = ArrayList<Long>()
    private val chineseDwells = ArrayList<Long>()
    private var shownChinese = ""
    private var shownAt = 0L
    private var shownEnglish = ""
    private var shownUntil = 0L
    private var erasedChars = 0
    private var rewrittenChars = 0
    private var englishPages = 0
    private var finals = 0
    private var translations = 0
    private var drafts = 0
    private var previews = 0
    private var queuePeak = 0
    private var slowest = 0L
    private var slowestDecode = 0L
    private var decodeTotal = 0L

    fun cue(cue: SubtitleCue) {
        slowestDecode = maxOf(slowestDecode, cue.decodeMs)
        decodeTotal += cue.decodeMs
        if (!cue.isFinal) { previews++; return }
        finals++
        if (cue.startMs !in covered) waiting += cue.startMs
    }
    fun queue(depth: Int) { queuePeak = maxOf(queuePeak, depth) }
    fun translation(cue: SubtitleCue) {
        slowest = maxOf(slowest, cue.translateMs)
        val now = nowMs()
        val visible = fitChinese(cue.chinese, VISIBLE_CHINESE_CHARS)
        if (visible != shownChinese) {
            if (shownChinese.isNotEmpty()) chineseDwells += now - shownAt
            val erased = shownChinese.length - shownChinese.commonPrefixWith(visible).length
            erasedChars += erased
            // Moving on to new speech is expected; only saying the same speech differently makes the reader lose their place.
            if (cue.startMs < shownUntil || sameSentence(shownEnglish, cue.english)) rewrittenChars += erased
            shownChinese = visible
            shownEnglish = cue.english
            shownUntil = cue.endMs
            shownAt = now
        }
        if (!cue.isFinal) {
            drafts++
            if (covered.add(cue.startMs)) lags += now - cue.startMs
            return
        }
        translations++
        settledLags += now - cue.endMs
        for (start in waiting.filter { it < cue.endMs }) {
            waiting -= start
            if (covered.add(start)) lags += now - start
        }
    }
    fun summary(): String {
        return "finals=$finals translated=$translations drafts=$drafts previews=$previews queuePeak=$queuePeak slowestTranslateMs=$slowest " +
            "decodeMs(mean=${decodeTotal / maxOf(finals + previews, 1)} max=$slowestDecode) " +
            "chineseLagMs${spread(lags)} settledChineseLagMs${spread(settledLags)} " +
            "chineseChanges=${chineseDwells.size} erasedChars=$erasedChars rewrittenChars=$rewrittenChars chineseDwellMs${spread(chineseDwells)} englishPages=$englishPages"
    }

    /** Feed each English page shown, so a run reports how often the whole card changed. */
    fun englishPage() { englishPages++ }

    private fun spread(values: List<Long>): String {
        val sorted = values.sorted()
        fun percentile(share: Int) = sorted.getOrElse((sorted.size - 1) * share / 100) { 0 }
        return "(p50=${percentile(50)} p90=${percentile(90)} max=${sorted.lastOrNull() ?: 0})"
    }
}
