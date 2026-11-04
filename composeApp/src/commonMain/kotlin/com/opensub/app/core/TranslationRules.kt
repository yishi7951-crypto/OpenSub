package com.opensub.app.core

/** More references than this make the request long, and a subtitle rarely holds more than one idiom. */
private const val MOST_REFERENCES = 4
/** "someone" is a person or thing in a few words: her, everyone, the new guy. */
private const val LONGEST_SLOT_WORDS = 3

private val WORD = Regex("[a-z0-9']+")
private val POSSESSIVES = setOf("my", "your", "his", "her", "its", "our", "their")
private val REFLEXIVES = setOf("myself", "yourself", "himself", "herself", "itself", "ourselves", "yourselves", "themselves")

/** Past and past participle of the verbs rules use; the rest add -ed. */
private val IRREGULAR = """
    be was were been; beat beat beaten; bite bit bitten; blow blew blown; break broke broken; bring brought brought;
    burn burnt burned; buy bought bought; catch caught caught; come came come; cut cut cut; do did done; draw drew drawn;
    drive drove driven; eat ate eaten; fall fell fallen; feel felt felt; fight fought fought; find found found; fly flew flown;
    get got gotten; give gave given; go went gone; grow grew grown; hang hung hung; have had had; hear heard heard;
    hit hit hit; hold held held; keep kept kept; know knew known; lay laid laid; lead led led; leave left left; let let let;
    lose lost lost; make made made; mean meant meant; meet met met; pay paid paid; put put put; ride rode ridden;
    run ran run; say said said; see saw seen; sell sold sold; send sent sent; set set set; shake shook shaken;
    shoot shot shot; show showed shown; sit sat sat; sleep slept slept; speak spoke spoken; spend spent spent;
    spill spilled spilt; stand stood stood; stick stuck stuck; take took taken; teach taught taught; tear tore torn;
    tell told told; think thought thought; throw threw thrown; wake woke woken; wear wore worn; win won won; write wrote written
""".split(';').map { it.trim().split(Regex("\\s+")) }.associate { it[0] to it.drop(1) }

/** Every form a verb takes: drive, drives, drove, driven, driving. Spellings that never occur do no harm. */
internal fun verbForms(verb: String): Set<String> {
    val stem = verb.removeSuffix("e")
    val doubled = verb + verb.last()
    val y = verb.length > 1 && verb.endsWith("y") && verb[verb.length - 2] !in "aeiou"
    return buildSet {
        add(verb)
        add(if (y) verb.dropLast(1) + "ies" else if (verb.endsWith("s") || verb.endsWith("sh") || verb.endsWith("ch") || verb.endsWith("x") || verb.endsWith("o")) verb + "es" else verb + "s")
        addAll(IRREGULAR[verb] ?: listOf(if (y) verb.dropLast(1) + "ied" else stem + "ed", doubled + "ed"))
        add(if (verb.endsWith("ie")) verb.dropLast(2) + "ying" else if (verb.endsWith("e") && !verb.endsWith("ee")) stem + "ing" else verb + "ing")
        add(doubled + "ing")
        if (verb == "be") addAll(listOf("am", "is", "are", "being"))
    }
}

/**
 * A rule set for translation: English idioms and phrasal verbs the model translates word by word, each with the Chinese it
 * should mean. The ones a request contains go to the model as reference translations, the model card's terminology template.
 * In a test on real subtitles nothing else in the prompt helped the 1.25-bit model: told that these are spoken subtitles
 * and to translate idioms by meaning, it translated the instructions instead about half the time. Given "drive someone away
 * translates to 把某人逼走" it wrote 我把她逼走了 for "I drove her away" (开车把她赶走了 without), and fitted the general
 * phrase to "driving everyone away" and "drives customers away" by itself. A reference the sentence does not need did no harm.
 *
 * A rule reads `english = 中文 # example`. In the English, `{verb}` stands for any form of the verb, `someone` and `something`
 * for one to three words (her, everyone, the new guy), `someone's` for a possessive and `oneself` for a reflexive; other
 * words must be there as they are, a hyphen matching a space too ("no brainer"). The example is a sentence the rule must
 * match, which the tests check.
 */
class TranslationRules(text: String) {
    class Rule(val english: String, val chinese: String, val example: String, internal val pattern: List<Set<String>?>)

    val rules: List<Rule> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.map(::parse)

    /** The reference translations for [english], as the model card writes them: "english translates to 中文". */
    fun references(english: String): List<String> {
        val words = WORD.findAll(english.lowercase().replace('’', '\'')).map { it.value }.toList()
        val found = ArrayList<Pair<IntRange, Rule>>()
        for (rule in rules) {
            for (start in words.indices) {
                val end = match(rule, words, start, 0) ?: continue
                found += (start until end) to rule
                break
            }
        }
        // A longer rule wins over one inside it: "like a closed book" over "a closed book".
        val kept = found.sortedByDescending { it.first.count() }.fold(ArrayList<Pair<IntRange, Rule>>()) { kept, candidate ->
            if (kept.none { candidate.first.first in it.first && candidate.first.last in it.first }) kept += candidate
            kept
        }
        return kept.take(MOST_REFERENCES).sortedBy { it.first.first }.map { "${it.second.english} translates to ${it.second.chinese}" }
    }

    /** Where [rule] ends if it matches [words] from [at], its element [element] onwards. */
    private fun match(rule: Rule, words: List<String>, at: Int, element: Int): Int? {
        if (element == rule.pattern.size) return at
        val wanted = rule.pattern[element]
        if (wanted != null) {
            if (at >= words.size) return null
            val word = words[at]
            val fits = word in wanted || (wanted === POSSESSIVE && (word in POSSESSIVES || word.endsWith("'s"))) ||
                (wanted === REFLEXIVE && word in REFLEXIVES)
            return if (fits) match(rule, words, at + 1, element + 1) else null
        }
        for (length in 1..LONGEST_SLOT_WORDS) {
            if (at + length > words.size) return null
            match(rule, words, at + length, element + 1)?.let { return it }
        }
        return null
    }

    private fun parse(line: String): Rule {
        val (english, rest) = line.split('=', limit = 2).map { it.trim() }
        val (chinese, example) = rest.split('#', limit = 2).map { it.trim() }
        val pattern = english.lowercase().split(Regex("[\\s-]+")).map { word ->
            when {
                word == "someone" || word == "something" -> null
                word == "someone's" -> POSSESSIVE
                word == "oneself" -> REFLEXIVE
                word.startsWith("{") && word.endsWith("}") -> verbForms(word.removeSurrounding("{", "}"))
                else -> setOf(word)
            }
        }
        return Rule(english.replace(Regex("[{}]"), ""), chinese, example, pattern)
    }

    private companion object {
        val POSSESSIVE = setOf("someone's")
        val REFLEXIVE = setOf("oneself")
    }
}
