package com.opensub.app.export

import com.opensub.app.core.ExportFormat
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleStyle
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleExportTest {
    private val cues = listOf(SubtitleCue(1, 1_000, 2_000, "Salt & <pepper>", 0, chinese = "盐和胡椒"))

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes() }
        }
        return result
    }

    @Test fun wordFileIsAValidPackageWithEscapedText() {
        val parts = entries(exportSubtitles(cues, SubtitleStyle(), ExportFormat.Word))
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), parts.keys)
        for (xml in parts.values) DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue("word/document.xml")))
        assertEquals("中文盐和胡椒EnglishSalt & <pepper>", document.documentElement.textContent)
    }

    @Test fun readingFormatsCarryTheTextWithoutTimes() {
        for (format in listOf(ExportFormat.Text, ExportFormat.Markdown)) {
            val text = exportSubtitles(cues, SubtitleStyle(), format).decodeToString()
            assertTrue("盐和胡椒" in text && "Salt" in text && "-->" !in text, text)
        }
    }

    @Test fun subtitleFormatKeepsTimes() {
        assertTrue("00:00:01,000 --> 00:00:02,000" in exportSubtitles(cues, SubtitleStyle(), ExportFormat.Srt).decodeToString())
    }
}
