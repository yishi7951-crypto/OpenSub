package com.opensub.app.export

import com.opensub.app.core.ExportFormat
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleStyle
import com.opensub.app.core.subtitleSections
import com.opensub.app.core.subtitlesToMarkdown
import com.opensub.app.core.subtitlesToSrt
import com.opensub.app.core.subtitlesToText
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun exportSubtitles(cues: List<SubtitleCue>, style: SubtitleStyle, format: ExportFormat): ByteArray = when (format) {
    ExportFormat.Word -> subtitlesToDocx(cues, style)
    ExportFormat.Text -> subtitlesToText(cues, style).encodeToByteArray()
    ExportFormat.Markdown -> subtitlesToMarkdown(cues, style).encodeToByteArray()
    ExportFormat.Srt -> subtitlesToSrt(cues, style).encodeToByteArray()
}

private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

private const val PACKAGE_RELATIONSHIPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

/** A .docx is a zip of XML parts; these three are the minimum Word accepts, so no library is needed. */
private fun subtitlesToDocx(cues: List<SubtitleCue>, style: SubtitleStyle): ByteArray {
    fun paragraph(text: String, bold: Boolean = false) = "<w:p><w:pPr><w:spacing w:after=\"200\"/></w:pPr><w:r>" +
        (if (bold) "<w:rPr><w:b/></w:rPr>" else "") + "<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r></w:p>"
    val paragraphs = subtitleSections(cues, style).joinToString("") { section ->
        section.heading?.let { paragraph(it, bold = true) }.orEmpty() + section.sentences.joinToString("") { paragraph(it) }
    }
    val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$paragraphs</w:body></w:document>"
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in listOf("[Content_Types].xml" to CONTENT_TYPES, "_rels/.rels" to PACKAGE_RELATIONSHIPS, "word/document.xml" to document)) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.encodeToByteArray())
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun escapeXml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
