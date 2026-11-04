package com.opensub.app.core

/** A token is a word, or a Chinese phrase between spaces; [glue] is the space written before it inside its piece. */
private class Token(val text: String, val glue: String)

private class Row(val token: Int, val text: String)

private val TOKENS = Regex("[^\\s\\u3000]+")

/**
 * [text] with its first row filled before a second one starts, so words already read stay put as the line grows. Same length,
 * so positions in it stay valid. Text already in rows, as [RollUp] makes it, stays as it is.
 */
fun filledRows(text: String, rowChars: Int): String {
    if (text.length <= rowChars || '\n' in text) return text
    val cut = text.lastIndexOf(' ', rowChars)
    return if (cut <= 0) text else text.replaceRange(cut, cut + 1, "\n")
}

/**
 * Two rows of one sentence ([SentenceCaption]), new text at the end of the second. A single piece too long for two rows rolls
 * up the way live captions do: the first row leaves and the second moves up, so text moves by a whole row and what was just
 * said stays up while the next words come. With several pieces, the oldest leaves whole to make room.
 *
 * Text comes in pieces, each under a key that only grows: a sentence as it is heard and then as its second reading heard it,
 * or one Chinese subtitle. Putting a key again replaces its piece; a piece that has left stays away. [between] goes
 * between pieces. Rows break between words, and between Chinese phrases where they can: a phrase too long for a row is
 * the only thing cut inside.
 */
class RollUp(private val rowChars: () -> Int, private val between: String) {
    private val keys = ArrayList<Long>()
    private val pieces = ArrayList<List<Token>>()
    private var topToken = 0
    private var rolledAway = Long.MIN_VALUE

    /** The rows, joined by a line break, now that [key] reads [text]. */
    fun put(key: Long, text: String): String {
        if (key <= rolledAway) return rows()
        val tokens = tokensOf(text)
        val at = keys.indexOf(key)
        if (at < 0) {
            keys += key
            pieces += tokens
        } else {
            pieces[at] = tokens
            if (at == 0) topToken = topToken.coerceAtMost(tokens.size)
        }
        return rows()
    }

    fun clear() {
        keys.clear()
        pieces.clear()
        topToken = 0
        rolledAway = Long.MIN_VALUE
    }

    /** Words, or Chinese phrases cut to fit a row. */
    private fun tokensOf(text: String): List<Token> {
        val width = rowChars().coerceAtLeast(1)
        var end = 0
        return TOKENS.findAll(text).flatMap { match ->
            val gap = text.substring(end, match.range.first)
            end = match.range.last + 1
            val glue = if ('　' in gap) "　" else if (gap.isNotEmpty()) " " else ""
            match.value.chunked(width).mapIndexed { index, part -> Token(part, if (index == 0) glue else "") }
        }.toList()
    }

    private fun rows(): String {
        while (true) {
            val rows = layout()
            if (rows.size <= 2) return rows.joinToString("\n") { it.text }
            if (pieces.size > 1) {
                // The first piece leaves whole: a Chinese subtitle comes all at once, and rolling by row pushed the start of
                // a long one ("同时，") off the top the moment it came.
                rolledAway = keys.removeAt(0)
                pieces.removeAt(0)
                topToken = 0
            } else topToken = rows[1].token
        }
    }

    private fun layout(): List<Row> {
        val rows = ArrayList<Row>()
        val row = StringBuilder()
        var rowToken = topToken
        for (piece in pieces.indices) {
            val tokens = pieces[piece]
            for (index in (if (piece == 0) topToken else 0) until tokens.size) {
                val token = tokens[index]
                val glue = if (row.isEmpty()) "" else if (index == 0) between else token.glue
                if (row.isNotEmpty() && row.length + glue.length + token.text.length > rowChars()) {
                    rows += Row(rowToken, row.toString())
                    row.clear()
                    rowToken = index
                    row.append(token.text)
                } else row.append(glue).append(token.text)
            }
        }
        if (row.isNotEmpty()) rows += Row(rowToken, row.toString())
        return rows
    }
}
