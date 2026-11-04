package ru.daybook.app

/**
 * Small Markdown checklist helper. The note body remains plain text, so the
 * checklist survives Drive sync and remains readable by other Daybook clients.
 */
data class NoteChecklistItem(
    val lineStart: Int,
    val text: String,
    val checked: Boolean
)

object NoteChecklist {
    private val itemPattern = Regex("^(\\s*)- \\[([ xX])\\] (.*)$")

    fun parse(body: String): List<NoteChecklistItem> {
        if (body.isEmpty()) return emptyList()
        val result = mutableListOf<NoteChecklistItem>()
        var lineStart = 0
        while (lineStart <= body.length) {
            val lineEnd = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
            val line = body.substring(lineStart, lineEnd)
            val match = itemPattern.matchEntire(line)
            if (match != null) {
                result += NoteChecklistItem(
                    lineStart = lineStart,
                    text = match.groupValues[3],
                    checked = match.groupValues[2].equals("x", ignoreCase = true)
                )
            }
            if (lineEnd == body.length) break
            lineStart = lineEnd + 1
        }
        return result
    }

    fun toggleLine(body: String, lineStart: Int): String {
        if (lineStart !in 0..body.length) return body
        val lineEnd = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
        val line = body.substring(lineStart, lineEnd)
        val match = itemPattern.matchEntire(line) ?: return body
        val markerIndex = lineStart + match.groupValues[1].length + 3
        if (markerIndex !in body.indices) return body
        val nextMarker = if (match.groupValues[2].equals("x", ignoreCase = true)) ' ' else 'x'
        return body.replaceRange(markerIndex, markerIndex + 1, nextMarker.toString())
    }

    fun appendItem(body: String): String = when {
        body.isEmpty() -> "- [ ] "
        body.endsWith('\n') -> body + "- [ ] "
        else -> body + "\n- [ ] "
    }
}
