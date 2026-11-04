package com.opensub.app.core

private const val SENTENCE_END = ".?!"
private const val CLAUSE_END = ",;:"
/** Between word starts, so this holds the word itself and a real pause after it. */
private const val PAUSE_SECONDS = 0.7f
/** Word starts are known but not word ends, so a sentence's end is its last word's start plus about one word. */
private const val LAST_WORD_MS = 350L
/** A name after a pause here is still the same sentence: "from all across America". */
private val LEADING_WORDS = setOf("all", "across", "around", "along", "toward", "towards", "both", "every", "each", "some", "any", "many", "most", "very")
/** More than the audio kept for a second reading could hold, and more than the translator handles well. */
private const val MOST_WORDS = 60
/** A word the readings could not agree on needs this many words after it before anything is built on it. */
private const val WORDS_AFTER_UNSURE = 2
/** A phrase carries on after these, so a sentence does not end on one. */
internal val LINKING_WORDS = setOf("the", "a", "an", "of", "to", "and", "or", "but", "in", "on", "at", "by", "for", "with", "from", "that", "which", "who",
    "whom", "whose", "as", "if", "than", "so", "because", "when", "while", "though", "although", "its", "his", "her", "their", "our", "my", "your", "this",
    "these", "those", "is", "are", "was", "were", "be", "been", "have", "has", "had", "will", "would", "shall", "should", "can", "could", "may", "might",
    "must", "do", "does", "did", "not", "before", "after", "during", "over", "under", "into", "about", "through", "between", "against", "without", "within",
    "upon", "until", "since", "where", "how", "why", "what")
internal val NUMBER_WORDS = ("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen " +
    "twenty thirty forty fifty sixty seventy eighty ninety hundred thousand million billion").split(' ').toSet()
/**
 * Nothing ends on these, whatever mark the model puts after them. Other small words can end a sentence ("where it needs to be.",
 * "move the offices further back in."), and a real talk ran two speakers' sentences together when every small word kept one open.
 */
internal val NEVER_LAST = setOf("the", "a", "an", "and", "or", "but", "nor", "of", "because", "if", "than", "which", "whose", "whom",
    "although", "though", "its", "their", "our", "my", "your")
private val NEGATIONS = setOf("not", "no", "never", "nor", "neither", "cannot", "without", "unless")
private val PRONOUN_I = setOf("i", "im", "ive", "ill", "id")

/**
 * Finds where sentences end in the words that have been heard, by what was said and never by how much fits on screen:
 * a sentence is what the translator needs to see whole, and the subtitle window decides how it is shown.
 * Every call returns any sentences that closed, as finals, then the open one as a growing cue with the same id.
 * A sentence closes when the next word is heard, not at its full stop: during a pause the model hears the audio end and
 * adds a full stop, and only the reading that hears speech resume can take it back.
 */
class SpokenSentences {
    private var id = 1L
    private val words = ArrayList<HeardWord>()
    private var shown = 0

    val openStartSeconds: Float? get() = words.firstOrNull()?.startSeconds

    fun take(update: RollingTranscript.Update): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        update.revisedLast?.let { text -> if (words.isNotEmpty()) words[words.lastIndex] = words.last().copy(text = text) }
        for (word in update.words) {
            closingAt(word)?.let { cues += close(it) }
            words += word
        }
        val nextIsCapital = update.nextText?.firstOrNull()?.isLowerCase() != true
        if (update.nextStartSeconds != null && nextIsCapital && (fullStop() || stopAfterSmallWord())) cues += close(words.size)
        else if (words.isNotEmpty() && (update.words.isNotEmpty() || update.revisedLast != null)) cues += cue(isFinal = false)
        return cues
    }

    /** The open sentence ends with a mark it can end on, after a small word or not: "with my shoes on." too. */
    val endsWithStop: Boolean get() = fullStop() || stopAfterSmallWord()

    /** The sentence still open, as it stands. */
    fun open(): SubtitleCue? = if (words.isEmpty()) null else cue(isFinal = false)

    fun allShown() { shown = words.size }

    /**
     * The open sentence if a word has settled since it last went on screen. Each word goes up as soon as it settles, the way
     * YouTube shows speech: the user wants captions on time, and waiting to show a phrase at once cost up to 0.8 s.
     * A full stop on the last word is not real yet: the model adds one wherever the audio it has ends.
     */
    fun ready(): SubtitleCue? {
        if (words.size <= shown) return null
        allShown()
        val steady = words.take(steadyWords()).joinToString(" ") { it.text }
        return cue(isFinal = false).let { it.copy(english = it.english.trimEnd { mark -> mark in SENTENCE_END }, sentenceEnglish = steady.trimEnd { mark -> mark in SENTENCE_END }) }
    }

    /**
     * The open sentence as far as a translation should build on it yet. Numbers, negations and words the readings disagreed
     * on change the meaning most when wrong, and small words at the end lead somewhere, so these wait for what comes next.
     */
    fun steadyWords(): Int {
        val unsure = words.indexOfLast { it.unsure }
        var count = if (unsure >= 0 && unsure >= words.size - WORDS_AFTER_UNSURE) unsure else words.size
        while (count > 0 && waitsForNext(words[count - 1])) count--
        return count
    }

    private fun waitsForNext(word: HeardWord): Boolean {
        val plain = word.text.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
        return word.text.last() !in CLAUSE_END && (plain in LINKING_WORDS || plain in NEGATIONS || plain in NUMBER_WORDS || plain.endsWith("n't") || plain.any { it.isDigit() })
    }

    fun finish(): List<SubtitleCue> = if (words.isEmpty()) emptyList() else listOf(close(words.size))

    /** How many words should close as a sentence now that [next] has come, or null to keep going. */
    private fun closingAt(next: HeardWord): Int? {
        val last = words.lastOrNull() ?: return null
        val mark = last.text.lastOrNull() ?: return null
        // A full stop before a small letter was only the audio ending: the sentence plainly goes on.
        if (fullStop() || stopAfterSmallWord()) return if (next.text.first().isLowerCase()) null else words.size
        // The model drops a full stop it only heard at the edge of a reading. A pause and a capital say the sentence ended.
        val paused = next.startSeconds - last.startSeconds >= PAUSE_SECONDS
        if (paused && mark.isLetterOrDigit() && !linking(last) && !capitalized(last) && capitalized(next) && plainWord(next) !in PRONOUN_I && plainWord(last) !in LEADING_WORDS) {
            words[words.lastIndex] = last.copy(text = "${last.text}.")
            return words.size
        }
        return if (words.size >= MOST_WORDS) lastClause() else null
    }

    /** A sentence too long to keep open ends at its last comma, if it has one in its second half. */
    private fun lastClause(): Int {
        val comma = words.indexOfLast { it.text.last() in CLAUSE_END } + 1
        return if (comma > words.size / 2) comma else words.size
    }

    private fun fullStop(): Boolean {
        val last = words.lastOrNull() ?: return false
        val mark = last.text.lastOrNull() ?: return false
        val sentence = words.joinToString(" ") { it.text }
        return mark in SENTENCE_END && !sentence.endsWith("..") && !endsWithAbbreviation(sentence) && !linking(last)
    }

    /**
     * A full stop the readings put after a small word a sentence can end on. It only counts once the next word is heard with
     * a capital: at the edge of a reading the model puts one after anything.
     */
    private fun stopAfterSmallWord(): Boolean {
        val last = words.lastOrNull() ?: return false
        val sentence = words.joinToString(" ") { it.text }
        return last.text.last() in SENTENCE_END && linking(last) && plainWord(last) !in NEVER_LAST && !sentence.endsWith("..") && !endsWithAbbreviation(sentence)
    }

    private fun plainWord(word: HeardWord) = word.text.lowercase().filter { it.isLetter() }

    private fun linking(word: HeardWord) = plainWord(word) in LINKING_WORDS

    /** Two capitalized words in a row are usually one name. */
    private fun capitalized(word: HeardWord) = word.text.firstOrNull()?.isUpperCase() == true

    private fun close(count: Int): SubtitleCue {
        val closing = words.take(count)
        repeat(count) { words.removeAt(0) }
        shown = (shown - count).coerceAtLeast(0)
        return cue(closing, isFinal = true).also { id++ }
    }

    private fun cue(isFinal: Boolean) = cue(words, isFinal)

    private fun cue(from: List<HeardWord>, isFinal: Boolean) = SubtitleCue(id, (from.first().startSeconds * 1000).toLong(),
        (from.last().startSeconds * 1000).toLong() + LAST_WORD_MS, from.joinToString(" ") { it.text }, 0, isFinal)
}
