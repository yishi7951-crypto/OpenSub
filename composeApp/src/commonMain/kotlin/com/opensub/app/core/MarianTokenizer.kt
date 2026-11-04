package com.opensub.app.core

const val SOURCE_LANGUAGE_TOKEN = ">>cmn_Hans<<"
private const val WORD_MARK = '▁'
private const val UNKNOWN_PENALTY = -10f

/**
 * Pure Kotlin stand-in for the two parts of Marian's tokenizer we need:
 * a SentencePiece unigram segmenter for English and a piece join for Chinese.
 * OPUS-MT shares one vocabulary and picks its target with a language token; Firefox's models need neither.
 */
class MarianTokenizer(
    sourceModel: ByteArray,
    vocabularyJson: String,
    targetVocabularyJson: String = vocabularyJson,
    languageToken: String? = SOURCE_LANGUAGE_TOKEN,
) {
    private val pieces = readPieces(sourceModel)
    private val longestPiece = pieces.keys.maxOf { it.length }
    private val vocabulary = readFlatJsonIds(vocabularyJson)
    private val targetVocabulary = readFlatJsonIds(targetVocabularyJson)
    private val names = targetVocabulary.entries.associate { it.value to it.key }
    private val unknown = vocabulary.getValue("<unk>")
    private val language = languageToken?.let(vocabulary::getValue)
    val endOfSentence = vocabulary.getValue("</s>")
    val padding = targetVocabulary.getValue("<pad>")

    fun encode(text: String): LongArray {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val prepared = words.joinToString("") { WORD_MARK + it }
        return (listOfNotNull(language) + segment(prepared).map { vocabulary[it] ?: unknown } + endOfSentence).toLongArray()
    }

    fun decode(ids: List<Long>): String {
        val text = ids.mapNotNull { names[it] }.filterNot { it.startsWith("<") || it.startsWith(">>") }
            .joinToString("").replace(WORD_MARK, ' ').trim()
        val joined = Regex(" *, *").replace(Regex("(?<=\\p{IsHan}) +(?=[\\p{IsHan}\\p{P}])").replace(text, ""), "，")
        return Regex("(?<=\\p{IsHan})[?!]").replace(joined) { if (it.value == "?") "？" else "！" }
    }

    /** Viterbi search for the highest scoring split; a character with no piece costs a fixed penalty. */
    private fun segment(text: String): List<String> {
        val best = FloatArray(text.length + 1) { Float.NEGATIVE_INFINITY }.also { it[0] = 0f }
        val from = IntArray(text.length + 1)
        for (start in text.indices) {
            if (best[start] == Float.NEGATIVE_INFINITY) continue
            if (best[start] + UNKNOWN_PENALTY > best[start + 1]) { best[start + 1] = best[start] + UNKNOWN_PENALTY; from[start + 1] = start }
            for (end in start + 1..minOf(text.length, start + longestPiece)) {
                val score = pieces[text.substring(start, end)] ?: continue
                if (best[start] + score > best[end]) { best[end] = best[start] + score; from[end] = start }
            }
        }
        val result = ArrayDeque<String>()
        var end = text.length
        while (end > 0) { result.addFirst(text.substring(from[end], end)); end = from[end] }
        return result
    }
}

/** ModelProto.pieces (field 1) holds SentencePiece {piece = 1, score = 2, type = 3}. Only normal pieces segment text. */
internal fun readPieces(model: ByteArray): Map<String, Float> {
    val result = HashMap<String, Float>()
    val reader = ProtoReader(model, 0, model.size)
    while (reader.hasMore()) {
        val (field, wire) = reader.tag()
        if (field != 1 || wire != 2) { reader.skip(wire); continue }
        val entry = reader.message()
        var piece = ""; var score = 0f; var type = 1
        while (entry.hasMore()) {
            val (inner, innerWire) = entry.tag()
            when (inner) {
                1 -> piece = entry.string()
                2 -> score = entry.float()
                3 -> type = entry.varint().toInt()
                else -> entry.skip(innerWire)
            }
        }
        if (type == 1 || type == 4) result[piece] = score
    }
    return result
}

private class ProtoReader(private val bytes: ByteArray, private var position: Int, private val end: Int) {
    fun hasMore() = position < end
    fun tag(): Pair<Int, Int> = varint().toInt().let { (it ushr 3) to (it and 7) }
    fun varint(): Long {
        var result = 0L; var shift = 0
        while (true) {
            val byte = bytes[position++].toInt()
            result = result or ((byte and 127).toLong() shl shift)
            if (byte and 128 == 0) return result
            shift += 7
        }
    }
    fun message(): ProtoReader { val size = varint().toInt(); return ProtoReader(bytes, position, position + size).also { position += size } }
    fun string(): String { val size = varint().toInt(); return bytes.decodeToString(position, position + size).also { position += size } }
    fun float(): Float {
        val bits = (0..3).fold(0) { value, index -> value or ((bytes[position + index].toInt() and 255) shl (index * 8)) }
        position += 4
        return Float.fromBits(bits)
    }
    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> position += 8
            2 -> { val size = varint().toInt(); position += size }
            5 -> position += 4
            else -> error("Unsupported protobuf wire type $wire")
        }
    }
}

/** vocab.json is one flat {"token": id} object, so a full JSON parser would be overkill. */
internal fun readFlatJsonIds(json: String): Map<String, Long> {
    val result = HashMap<String, Long>()
    var index = json.indexOf('{') + 1
    while (true) {
        index = json.indexOf('"', index)
        if (index < 0) return result
        val key = StringBuilder()
        index++
        while (json[index] != '"') {
            if (json[index] != '\\') { key.append(json[index++]); continue }
            val escaped = json[index + 1]
            index += 2
            when (escaped) {
                'n' -> key.append('\n'); 't' -> key.append('\t'); 'r' -> key.append('\r')
                'b' -> key.append('\b'); 'f' -> key.append('\u000C')
                'u' -> { key.append(json.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                else -> key.append(escaped)
            }
        }
        index = json.indexOf(':', index) + 1
        while (json[index].isWhitespace()) index++
        val digits = index
        while (json[index].isDigit()) index++
        result[key.toString()] = json.substring(digits, index).toLong()
    }
}
