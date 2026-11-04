package com.opensub.app.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TranslationTest {
    private fun entry(piece: String, score: Float, type: Int = 1): ByteArray {
        val text = piece.encodeToByteArray()
        val bits = score.toRawBits()
        val body = byteArrayOf(0x0A, text.size.toByte()) + text + byteArrayOf(0x15) +
            ByteArray(4) { (bits ushr (it * 8)).toByte() } + byteArrayOf(0x18, type.toByte())
        return byteArrayOf(0x0A, body.size.toByte()) + body
    }

    private val vocabulary = """{"</s>": 0, "<unk>": 1, ">>cmn_Hans<<": 2, "▁go": 3, "▁g": 4, "o": 5, "▁我们": 6, "去": 7, ",": 8, "<pad>": 9}"""
    private val pieces = entry("<unk>", 0f, 2) + entry("▁go", -1f) + entry("▁g", -2f) + entry("o", -2f) + entry("▁", -5f)
    private val tokenizer = MarianTokenizer(pieces, vocabulary)

    @Test fun encodingPrefersTheBestScoringSplitAndWrapsSpecialTokens() {
        assertContentEquals(longArrayOf(2, 3, 0), tokenizer.encode("  go "))
    }

    @Test fun unknownCharactersBecomeUnknownTokens() {
        assertContentEquals(longArrayOf(2, 3, 1, 0), tokenizer.encode("go?"))
    }

    @Test fun decodingSkipsSpecialTokensAndRemovesSpacesInsideChinese() {
        assertEquals("我们去，去", tokenizer.decode(listOf(2, 6, 6.minus(6) + 7, 8, 7, 0)))
    }

    @Test fun aSeparateTargetVocabularyNeedsNoLanguageToken() {
        val target = """{"</s>": 0, "▁你好": 1, "吗": 2, "?": 3, "!": 4, "<pad>": 5}"""
        val separate = MarianTokenizer(pieces, vocabulary, target, languageToken = null)
        assertContentEquals(longArrayOf(3, 0), separate.encode("go"))
        assertEquals(5L, separate.padding)
        assertEquals("你好吗？", separate.decode(listOf(1, 2, 3)))
        assertEquals("你好！", separate.decode(listOf(1, 4)))
    }

    private class Script(private val count: Int) : AudioSource, SpeechDetector, SpeechRecognizer {
        override fun frames(): Flow<PcmFrame> = flow { repeat(count) { emit(PcmFrame(floatArrayOf(it.toFloat()), it.toLong())) } }
        override fun stop() {}
        override fun accept(frame: PcmFrame) = listOf(Utterance(frame.samples, frame.startSample))
        override fun flush() = emptyList<Utterance>()
        override suspend fun recognize(utterance: Utterance) = "Sentence ${utterance.startSample}."
        override fun close() {}
    }

    private class Echo : Translator {
        override suspend fun translate(english: String) = "译 $english"
        override fun close() {}
    }

    @Test fun finalsAreTranslatedInOrderWithoutChangingTheirIds() = runTest {
        val script = Script(3)
        val cues = ArrayList<SubtitleCue>()
        val translated = ArrayList<SubtitleCue>()
        CaptionPipeline().run(script, script, script, translator = Echo(), onTranslation = { translated += it }, onCue = { cues += it })
        assertEquals(listOf("译 Sentence 0.", "译 Sentence 1.", "译 Sentence 2."), translated.map { it.chinese })
        assertEquals(cues.map { it.id }, translated.map { it.id })
        assertEquals(listOf("", "", ""), cues.map { it.chinese })
    }

    private class Parts : AudioSource, SpeechDetector, SpeechRecognizer {
        private val text = listOf("In a decade of hope,", "and of fear.")
        override fun frames(): Flow<PcmFrame> = flow { repeat(2) { emit(PcmFrame(floatArrayOf(0f), it * 16_000L)) } }
        override fun stop() {}
        override fun accept(frame: PcmFrame) = listOf(Utterance(FloatArray(8000), frame.startSample))
        override fun flush() = emptyList<Utterance>()
        override suspend fun recognize(utterance: Utterance) = text[(utterance.startSample / 16_000).toInt()]
        override fun close() {}
    }

    @Test fun aLaterPartRetranslatesTheWholeSentence() = runTest {
        val parts = Parts()
        val translated = ArrayList<SubtitleCue>()
        CaptionPipeline().run(parts, parts, parts, translator = Echo(), onTranslation = { translated += it }, onCue = {})
        val last = translated.last()
        assertEquals("译 In a decade of hope, and of fear.", last.chinese)
        assertEquals(1L, last.id)
        assertEquals(1500L, last.endMs)
    }

    @Test fun pipelineStillWorksWithoutATranslator() = runTest {
        val script = Script(2)
        val cues = ArrayList<SubtitleCue>()
        CaptionPipeline().run(script, script, script, onCue = { cues += it })
        assertEquals(2, cues.size)
    }
}
