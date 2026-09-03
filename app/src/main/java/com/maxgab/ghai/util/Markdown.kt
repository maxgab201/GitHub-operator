package com.maxgab.ghai.util

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Block model ----------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val lang: String?, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

private fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    val paragraphBuffer = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraphBuffer.joinToString("\n"))
            paragraphBuffer.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim().ifBlank { null }
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code += lines[i]
                    i++
                }
                blocks += MdBlock.CodeBlock(lang, code.joinToString("\n"))
            }
            trimmed.isBlank() -> flushParagraph()
            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += MdBlock.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flushParagraph()
                blocks += MdBlock.Divider
            }
            isTableHeader(lines, i) -> {
                flushParagraph()
                val header = splitTableRow(trimmed)
                i += 2 // header + separator
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += splitTableRow(lines[i].trim())
                    i++
                }
                i--
                blocks += MdBlock.Table(header, rows)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flushParagraph()
                val items = mutableListOf(trimmed.drop(2).trim())
                while (i + 1 < lines.size && lines[i + 1].trim().let {
                        it.startsWith("- ") || it.startsWith("* ") || it.startsWith("+ ")
                    }) {
                    i++
                    items += lines[i].trim().drop(2).trim()
                }
                blocks += MdBlock.BulletList(items)
            }
            Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> {
                flushParagraph()
                val items = mutableListOf(trimmed.replaceFirst(Regex("^\\d+\\.\\s"), ""))
                while (i + 1 < lines.size && Regex("^\\d+\\.\\s").containsMatchIn(lines[i + 1].trim())) {
                    i++
                    items += lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s"), "")
                }
                blocks += MdBlock.NumberedList(items)
            }
            else -> paragraphBuffer += line
        }
        i++
    }
    flushParagraph()
    return blocks
}

private fun isTableHeader(lines: List<String>, index: Int): Boolean {
    val header = lines.getOrNull(index)?.trim() ?: return false
    val separator = lines.getOrNull(index + 1)?.trim() ?: return false
    if (!header.contains("|")) return false
    return Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$").matches(separator)
}

private fun splitTableRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

// ---------- Inline styling ----------

private val inlineRegex = Regex("(\\*\\*.+?\\*\\*|__.+?__|\\*[^*]+?\\*|_[^_]+?_|`[^`]+?`|\\[[^\\]]+?\\]\\([^)]+?\\))")

private fun buildInlineAnnotatedString(text: String, codeColor: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (match in inlineRegex.findAll(text)) {
            if (match.range.first > last) append(text.substring(last, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.removePrefix("**").removeSuffix("**"))
                }
                token.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.removePrefix("__").removeSuffix("__"))
                }
                token.startsWith("`") -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor.copy(alpha = 0.15f))
                ) {
                    append(token.removePrefix("`").removeSuffix("`"))
                }
                token.startsWith("[") -> {
                    val label = token.substringAfter("[").substringBefore("]")
                    withStyle(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append(label)
                    }
                }
                token.startsWith("*") || token.startsWith("_") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.trim('*', '_'))
                }
                else -> append(token)
            }
            last = match.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

// ---------- Composables ----------

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = buildInlineAnnotatedString(block.text, codeColor, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                )
                is MdBlock.Paragraph -> Text(
                    text = buildInlineAnnotatedString(block.text, codeColor, linkColor),
                    style = MaterialTheme.typography.bodyLarge
                )
                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .size(width = 3.dp, height = 18.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Text(
                        text = buildInlineAnnotatedString(block.text, codeColor, linkColor),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = LocalContentColor.current.copy(alpha = 0.8f)
                    )
                }
                MdBlock.Divider -> Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text("•  ", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = buildInlineAnnotatedString(item, codeColor, linkColor),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                is MdBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { idx, item ->
                        Row {
                            Text("${idx + 1}.  ", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = buildInlineAnnotatedString(item, codeColor, linkColor),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                is MdBlock.CodeBlock -> CodeBlockView(block.lang, block.code)
                is MdBlock.Table -> MarkdownTable(block.header, block.rows)
            }
        }
    }
}

@Composable
private fun CodeBlockView(lang: String?, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(4.dp)
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = lang ?: "código",
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp)
            )
            Box(Modifier) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar código", modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                style = LocalTextStyle.current.copy(lineHeight = 18.sp)
            )
        }
    }
}

@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    val columns = header.size
    if (columns == 0) return
    val borderColor = MaterialTheme.colorScheme.outline
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Layout(content = {
            header.forEach { TableCell(it, bold = true, borderColor = borderColor) }
            rows.forEach { row ->
                for (c in 0 until columns) {
                    TableCell(row.getOrElse(c) { "" }, bold = false, borderColor = borderColor)
                }
            }
        }) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(loose) }
            val totalRows = rows.size + 1
            val colWidths = IntArray(columns)
            val rowHeights = IntArray(totalRows)
            for (r in 0 until totalRows) {
                for (c in 0 until columns) {
                    val idx = r * columns + c
                    if (idx < placeables.size) {
                        colWidths[c] = maxOf(colWidths[c], placeables[idx].width)
                        rowHeights[r] = maxOf(rowHeights[r], placeables[idx].height)
                    }
                }
            }
            val width = colWidths.sum()
            val height = rowHeights.sum()
            layout(width, height) {
                var y = 0
                for (r in 0 until totalRows) {
                    var x = 0
                    for (c in 0 until columns) {
                        val idx = r * columns + c
                        if (idx < placeables.size) placeables[idx].place(x, y)
                        x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, bold: Boolean, borderColor: Color) {
    Box(
        Modifier
            .border(0.5.dp, borderColor)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
