package com.opensub.app.core

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    Word("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    Text("TXT", "txt", "text/plain"),
    Markdown("Markdown", "md", "text/markdown"),
    Srt("SRT 字幕", "srt", "application/x-subrip"),
}

private const val LINE_END = "\r\n"
private const val BYTE_ORDER_MARK = "﻿"

/**
 * Follows the on-screen mode, so a file matches what was watched. Sentences still awaiting a translation
 * fall back to English. Times count from when capture started, not from the video's own timeline.
 */
fun subtitlesToSrt(cues: List<SubtitleCue>, style: SubtitleStyle): String = buildString {
    append(BYTE_ORDER_MARK)
    cues.forEachIndexed { index, cue ->
        append(index + 1).append(LINE_END)
        append(srtTime(cue.startMs)).append(" --> ").append(srtTime(maxOf(cue.endMs, cue.startMs + 1))).append(LINE_END)
        for (line in subtitleLines(cue, style)) append(line).append(LINE_END)
        append(LINE_END)
    }
}

data class SubtitleSection(val heading: String?, val sentences: List<String>)

/**
 * Reading formats keep each language whole: bilingual becomes all Chinese, then all English.
 * A timed subtitle file cannot do this, because its clock would jump back halfway through, so SRT stays line by line.
 */
fun subtitleSections(cues: List<SubtitleCue>, style: SubtitleStyle): List<SubtitleSection> = when (style.mode) {
    SubtitleMode.English -> listOf(SubtitleSection(null, cues.map { it.english }))
    SubtitleMode.Chinese -> listOf(SubtitleSection(null, cues.map { it.chinese.ifEmpty { it.english } }))
    SubtitleMode.Bilingual -> listOf(SubtitleSection("中文", cues.map { it.chinese }.filter { it.isNotEmpty() }), SubtitleSection("English", cues.map { it.english }))
}

/** Plain reading text: one line per sentence, no times. */
fun subtitlesToText(cues: List<SubtitleCue>, style: SubtitleStyle): String = BYTE_ORDER_MARK +
    subtitleSections(cues, style).joinToString(LINE_END + LINE_END) { section ->
        (listOfNotNull(section.heading) + section.sentences).joinToString(LINE_END)
    } + LINE_END

fun subtitlesToMarkdown(cues: List<SubtitleCue>, style: SubtitleStyle): String = "# OpenSub 字幕\n\n" +
    subtitleSections(cues, style).joinToString("\n\n") { section ->
        (listOfNotNull(section.heading?.let { "## $it" }) + section.sentences).joinToString("\n\n")
    } + "\n"

fun subtitleLines(cue: SubtitleCue, style: SubtitleStyle): List<String> = when {
    style.mode == SubtitleMode.English || cue.chinese.isEmpty() -> listOf(cue.english)
    style.mode == SubtitleMode.Chinese -> listOf(cue.chinese)
    style.chineseFirst -> listOf(cue.chinese, cue.english)
    else -> listOf(cue.english, cue.chinese)
}

private fun srtTime(ms: Long): String {
    fun pad(value: Long, width: Int) = value.toString().padStart(width, '0')
    return "${pad(ms / 3_600_000, 2)}:${pad(ms / 60_000 % 60, 2)}:${pad(ms / 1000 % 60, 2)},${pad(ms % 1000, 3)}"
}
