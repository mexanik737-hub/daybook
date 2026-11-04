package ru.daybook.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

/**
 * Displays supported Markdown formatting without exposing its markers while editing.
 * Source remains plain Markdown so existing Drive documents remain compatible.
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
            var quote = false
            var bullet: String? = null

            when {
                source.startsWith("## ", lineStart) -> { heading = true; contentStart += 3 }
                source.startsWith("> ", lineStart) -> { quote = true; contentStart += 2 }
                source.startsWith("- [ ] ", lineStart) -> { bullet = "☐ "; contentStart += 6 }
                source.startsWith("- [x] ", lineStart, ignoreCase = true) -> { bullet = "☑ "; contentStart += 6 }
                source.startsWith("- ", lineStart) -> { bullet = "• "; contentStart += 2 }
            }

            for (i in lineStart until contentStart) sourceToOutput[i] = output.length
            bullet?.forEach { appendVisible(it, lineStart) }
            if (quote) "❝ ".forEach { appendVisible(it, lineStart) }
            val lineOutputStart = output.length
            var i = contentStart
            var boldStart = -1
            var italicStart = -1
            var strikeStart = -1
            var codeStart = -1

            while (i < newline) {
                sourceToOutput[i] = output.length
                val marker = when {
                    i + 1 < newline && source.startsWith("**", i) -> "**"
                    i + 1 < newline && source.startsWith("~~", i) -> "~~"
                    source[i] == '_' && (italicStart >= 0 ||
                        ((i == contentStart || !source[i - 1].isLetterOrDigit()) &&
                            i + 1 < newline && source[i + 1].isLetterOrDigit())) -> "_"
                    source[i] == '`' -> "`"
                    else -> null
                }
                if (marker != null) {
                    for (offset in 1 until marker.length) sourceToOutput[i + offset] = output.length
                    when (marker) {
                        "**" -> {
                            if (boldStart < 0) boldStart = output.length else {
                                if (output.length > boldStart) spans.add(Triple(boldStart, output.length, SpanStyle(fontWeight = FontWeight.Bold)))
                                boldStart = -1
                            }
                        }
                        "~~" -> {
                            if (strikeStart < 0) strikeStart = output.length else {
                                if (output.length > strikeStart) spans.add(Triple(strikeStart, output.length, SpanStyle(textDecoration = TextDecoration.LineThrough)))
                                strikeStart = -1
                            }
                        }
                        "_" -> {
                            if (italicStart < 0) italicStart = output.length else {
                                if (output.length > italicStart) spans.add(Triple(italicStart, output.length, SpanStyle(fontStyle = FontStyle.Italic)))
                                italicStart = -1
                            }
                        }
                        "`" -> {
                            if (codeStart < 0) codeStart = output.length else {
                                if (output.length > codeStart) spans.add(Triple(codeStart, output.length,
                                    SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x443F3A52), color = Color(0xFFD8C9FF))))
                                codeStart = -1
                            }
                        }
                    }
                    i += marker.length
                    continue
                }
                appendVisible(source[i], i)
                i++
            }
            sourceToOutput[newline] = output.length
            if (boldStart >= 0 && output.length > boldStart) spans.add(Triple(boldStart, output.length, SpanStyle(fontWeight = FontWeight.Bold)))
            if (italicStart >= 0 && output.length > italicStart) spans.add(Triple(italicStart, output.length, SpanStyle(fontStyle = FontStyle.Italic)))
            if (strikeStart >= 0 && output.length > strikeStart) spans.add(Triple(strikeStart, output.length, SpanStyle(textDecoration = TextDecoration.LineThrough)))
            if (codeStart >= 0 && output.length > codeStart) spans.add(Triple(codeStart, output.length,
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x443F3A52), color = Color(0xFFD8C9FF))))
            if (heading && output.length > lineOutputStart) spans.add(Triple(lineOutputStart, output.length,
                SpanStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold)))
            if (quote && output.length > lineOutputStart) spans.add(Triple(lineOutputStart, output.length,
                SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFC9B8E8))))
            if (newline < source.length) {
                appendVisible('\n', newline)
                lineStart = newline + 1
            } else break
        }
        sourceToOutput[source.length] = output.length

        val styled = AnnotatedString.Builder(output.toString()).apply {
            spans.forEach { (start, end, style) ->
                if (start < end && start < output.length) addStyle(style, start.coerceAtLeast(0), end.coerceAtMost(output.length))
            }
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
