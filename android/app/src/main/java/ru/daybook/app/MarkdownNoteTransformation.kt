package ru.daybook.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

/**
 * Renders the lightweight Markdown syntax used by Daybook's note toolbar
 * without exposing formatting markers while the note is being edited.
 * The underlying text remains portable plain Markdown for Drive sync.
 */
class MarkdownNoteTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val output = StringBuilder()
        val sourceToOutput = IntArray(source.length + 1)
        val outputToSource = ArrayList<Int>()
        val spans = ArrayList<Triple<Int, Int, SpanStyle>>()

        fun appendVisible(char: Char, sourceIndex: Int) {
            output.append(char)
            outputToSource.add(sourceIndex)
        }

        var lineStart = 0
        while (lineStart <= source.length) {
            val newline = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            var contentStart = lineStart
            var heading = false
            var bullet: String? = null

            when {
                source.startsWith("## ", lineStart) -> { heading = true; contentStart += 3 }
                source.startsWith("- [ ] ", lineStart) -> { bullet = "☐ "; contentStart += 6 }
                source.startsWith("- [x] ", lineStart, ignoreCase = true) -> { bullet = "☑ "; contentStart += 6 }
                source.startsWith("- ", lineStart) -> { bullet = "• "; contentStart += 2 }
            }

            for (i in lineStart until contentStart) sourceToOutput[i] = output.length
            bullet?.forEach { appendVisible(it, lineStart) }
            val lineOutputStart = output.length
            var i = contentStart
            var boldStart = -1
            var italicStart = -1

            while (i < newline) {
                sourceToOutput[i] = output.length
                if (i + 1 < newline && source.startsWith("**", i)) {
                    sourceToOutput[i + 1] = output.length
                    if (boldStart < 0) boldStart = output.length else {
                        if (output.length > boldStart) spans.add(Triple(boldStart, output.length, SpanStyle(fontWeight = FontWeight.Bold)))
                        boldStart = -1
                    }
                    i += 2
                    continue
                }
                if (source[i] == '_' && (i == contentStart || !source[i - 1].isLetterOrDigit()) &&
                    (i + 1 < newline && source[i + 1].isLetterOrDigit())) {
                    if (italicStart < 0) italicStart = output.length else {
                        if (output.length > italicStart) spans.add(Triple(italicStart, output.length, SpanStyle(fontStyle = FontStyle.Italic)))
                        italicStart = -1
                    }
                    i++
                    continue
                }
                appendVisible(source[i], i)
                i++
            }
            sourceToOutput[newline] = output.length
            if (boldStart >= 0 && output.length > boldStart) {
                spans.add(Triple(boldStart, output.length, SpanStyle(fontWeight = FontWeight.Bold)))
            }
            if (italicStart >= 0 && output.length > italicStart) {
                spans.add(Triple(italicStart, output.length, SpanStyle(fontStyle = FontStyle.Italic)))
            }
            if (heading && output.length > lineOutputStart) {
                spans.add(Triple(lineOutputStart, output.length, SpanStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold)))
            }
            if (newline < source.length) {
                appendVisible('\n', newline)
                lineStart = newline + 1
            } else break
        }
        sourceToOutput[source.length] = output.length

        val styled = AnnotatedString.Builder(output.toString()).apply {
            spans.forEach { (start, end, style) -> addStyle(style, start.coerceAtLeast(0), end.coerceAtMost(output.length)) }
        }.toAnnotatedString()

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                sourceToOutput[offset.coerceIn(0, source.length)].coerceIn(0, output.length)

            override fun transformedToOriginal(offset: Int): Int {
                if (outputToSource.isEmpty()) return 0
                if (offset >= outputToSource.size) return source.length
                return outputToSource[offset.coerceAtLeast(0)].coerceIn(0, source.length)
            }
        }
        return TransformedText(styled, mapping)
    }
}
