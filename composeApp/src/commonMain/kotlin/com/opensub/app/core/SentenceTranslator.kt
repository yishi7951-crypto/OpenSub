package com.opensub.app.core

private const val SENTENCE_END = ".?!"
private const val CHINESE_END = "。！？…"

/** No full stops: continuing after one tells the model the sentence is over, and it starts translating again from the top. */
private const val CLAUSE_END = "，、；："
private const val REMEMBERED_SENTENCES = 4
private const val MIN_SHARED_WORDS = 3
private const val SHARED_SHARE = 0.6
private const val MOST_OF_THE_CHINESE = 0.6
private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "vs", "etc", "no", "e.g", "i.e")
private val VOCATIVE = Regex(", ((?:Mr\\.?|Mister|mister|Mrs\\.?|Madam) [A-Z]\\w+|Sir|[Ll]adies and gentlemen|[Mm]y friends|[Mm]y fellow \\w+),")

/**
 * OPUS-MT is trained on single sentences. Given two or more at once it drops whole clauses without warning, so each
 * sentence is translated alone. With a [CachingTranslator] underneath, a growing group only pays for its newest sentence.
 *
 * Translating a sentence afresh each time it grows rewrites the Chinese already being read, so a growing sentence
 * continues from the last clause of its previous version. Only the words after that clause can still change.
 *
 * A long sentence closes early, and its second part makes no sense alone ("by which alone the shipping could approach").
 * A model that can continue reads both parts and carries on from the first part's Chinese, so the part keeps its subject.
 */
class SentenceTranslator(private val delegate: Translator) : Translator {
    private class Heard(var english: String, var chinese: String, val before: Heard?)

    private val recent = ArrayDeque<Heard>()

    override suspend fun translate(english: String): String {
        val parts = splitSentences(english).map { steady(it).trim() }.filter { it.isNotEmpty() }
        return parts.mapIndexed { index, part -> if (index < parts.lastIndex && part.last() !in CHINESE_END) "$part。" else part }.joinToString("")
    }

    private suspend fun steady(sentence: String): String {
        // The same words with other punctuation: a sentence read at a pause, then again when it closes.
        // Not if only one of them is finished: Chinese for a finished sentence has invented an ending for an unfinished one.
        recent.lastOrNull { plainWords(it.english) == plainWords(sentence) && finished(it.english) == finished(sentence) }?.let { return it.chinese }
        val growing = recent.lastOrNull()?.takeIf { onlyGrew(it.english, sentence) } ?: recent.lastOrNull { sameSentence(it.english, sentence) }
        if (growing == null) {
            val before = recent.lastOrNull()?.takeIf { delegate.continues && it.english.last() !in SENTENCE_END }
            val chinese = withoutInventedEnding(sentence, say(sentence, "", before))
            recent.addLast(Heard(sentence, chinese, before))
            if (recent.size > REMEMBERED_SENTENCES) recent.removeFirst()
            return chinese
        }
        // A word changed in what was already translated: its Chinese is wrong too, so nothing of it can be kept.
        val chinese = withoutInventedEnding(sentence, say(sentence, if (onlyGrew(growing.english, sentence)) throughLastClause(growing.chinese) else "", growing.before))
        growing.english = sentence
        growing.chinese = chinese
        return chinese
    }

    /**
     * Cut off mid-phrase, the model makes up the rest, and what follows will say the rest. Finished clauses stay, unless
     * that leaves too little: a caption with most of its Chinese missing reads as a translation that fell behind.
     */
    private fun withoutInventedEnding(sentence: String, chinese: String): String {
        if (!delegate.continues || !sentence.last().isLetterOrDigit()) return chinese
        val finished = throughLastClause(chinese)
        return if (finished.length >= chinese.length * MOST_OF_THE_CHINESE) finished else chinese.trimEnd('。')
    }

    private suspend fun say(sentence: String, shown: String, before: Heard?): String {
        val english = vocativeFirst(sentence)
        if (before == null) return if (shown.isEmpty()) delegate.translate(english) else delegate.continueFrom(english, shown)
        val lead = before.chinese.trimEnd { it in CHINESE_END || it in CLAUSE_END } + "，"
        return delegate.continueFrom("${before.english} $english", lead + shown).removePrefix(lead)
    }

    override fun close() = delegate.close()
}

private fun finished(sentence: String) = sentence.last() in SENTENCE_END

private fun plainWords(text: String) = text.lowercase().split(' ').map { word -> word.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }

private fun onlyGrew(earlier: String, later: String): Boolean {
    val first = plainWords(earlier)
    return plainWords(later).take(first.size) == first
}

/** True when [later] is [earlier] with more words, or with a few words heard differently. */
internal fun sameSentence(earlier: String, later: String): Boolean {
    val first = plainWords(earlier)
    val second = plainWords(later)
    val shared = minOf(first.size, second.size)
    if (shared < MIN_SHARED_WORDS) return false
    return (0 until shared).count { first[it] == second[it] } >= shared * SHARED_SHARE
}

/**
 * "When, Mr Speaker, I asked" reads to the model as the Speaker asking, and the Chinese takes the wrong subject.
 * At the front of the sentence the same words are plainly someone being addressed.
 */
internal fun vocativeFirst(sentence: String): String {
    val found = VOCATIVE.find(sentence) ?: return sentence
    return found.groupValues[1].replaceFirstChar { it.uppercase() } + ", " + sentence.removeRange(found.range)
}

private fun throughLastClause(chinese: String) = chinese.take(chinese.indexOfLast { it in CLAUSE_END } + 1)

/** Splits after . ? ! when the next word starts a sentence. "3.5", "U.S.", "Mr. Smith" and "..." stay whole. */
fun splitSentences(text: String): List<String> {
    val sentences = ArrayList<String>()
    var start = 0
    for (index in 1 until text.length) {
        if (!text[index].isWhitespace() || text[index - 1] !in SENTENCE_END) continue
        val next = text.substring(index).firstOrNull { !it.isWhitespace() } ?: continue
        val sentence = text.substring(start, index)
        if (next.isLowerCase() || sentence.endsWith("..") || endsWithAbbreviation(sentence)) continue
        sentences += sentence.trim()
        start = index
    }
    return sentences + listOf(text.substring(start).trim()).filter { it.isNotEmpty() }
}

internal fun endsWithAbbreviation(sentence: String): Boolean {
    val word = sentence.dropLast(1).substringAfterLast(' ').trimStart('(', '"', '\'').lowercase()
    return (word.length == 1 && word[0].isLetter() && word != "i") || word in ABBREVIATIONS
}

/** How many tokens at the end of [tokens] only repeat what came before, or 0: the mark of a decoder stuck in a loop. */
fun repeatedTail(tokens: List<Long>, repeats: Int = 3, longestCycle: Int = 8): Int {
    for (cycle in 1..longestCycle) {
        val length = cycle * repeats
        if (tokens.size < length) break
        val tail = tokens.subList(tokens.size - length, tokens.size)
        if (tail.indices.all { it < cycle || tail[it] == tail[it - cycle] }) return length - cycle
    }
    return 0
}
