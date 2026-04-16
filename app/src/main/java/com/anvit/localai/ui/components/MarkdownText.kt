package com.anvit.localai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvit.localai.ui.theme.*

/**
 * A lightweight Markdown renderer for assistant message bubbles.
 *
 * Supports:
 *   - **bold**, *italic*, `inline code`
 *   - # / ## / ### headers
 *   - Fenced code blocks (```...```)
 *   - Unordered bullet lists (- / * / •)
 *   - Ordered lists (1. 2. 3.)
 *   - Horizontal rules (---)
 *   - Plain paragraphs with blank-line separation
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val (size, weight) = when (block.level) {
                        1 -> 22.sp to FontWeight.Bold
                        2 -> 20.sp to FontWeight.SemiBold
                        else -> 18.sp to FontWeight.SemiBold
                    }
                    Text(
                        text = buildInlineAnnotated(block.content, color),
                        fontSize = size,
                        fontWeight = weight,
                        color = color,
                        modifier = Modifier.padding(top = if (block.level == 1) 4.dp else 2.dp)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineAnnotated(block.content, color),
                        style = LocalTextStyle.current,
                        fontSize = 15.sp,
                        color = color,
                        lineHeight = 24.sp
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Surface3)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = TealLight,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
                is MarkdownBlock.BulletItem -> {
                    Row(modifier = Modifier.padding(start = (block.indent * 12).dp)) {
                        Text(
                            text = "•",
                            color = TealPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(16.dp)
                        )
                        Text(
                            text = buildInlineAnnotated(block.content, color),
                            fontSize = 15.sp,
                            color = color,
                            lineHeight = 24.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.OrderedItem -> {
                    Row(modifier = Modifier.padding(start = (block.indent * 12).dp)) {
                        Text(
                            text = "${block.number}.",
                            color = TealPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 24.dp)
                        )
                        Text(
                            text = buildInlineAnnotated(block.content, color),
                            fontSize = 15.sp,
                            color = color,
                            lineHeight = 24.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BorderSubtle)
                    )
                }
            }
        }
    }
}

// ─── Block types ─────────────────────────────────────────────────────────────

private sealed class MarkdownBlock {
    data class Heading(val level: Int, val content: String) : MarkdownBlock()
    data class Paragraph(val content: String) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String = "") : MarkdownBlock()
    data class BulletItem(val content: String, val indent: Int = 0) : MarkdownBlock()
    data class OrderedItem(val number: Int, val content: String, val indent: Int = 0) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
}

// ─── Block parser ─────────────────────────────────────────────────────────────

private val headingRegex    = Regex("""^(#{1,3})\s+(.*)""")
private val bulletRegex     = Regex("""^(\s*)[-*•]\s+(.+)""")
private val orderedRegex    = Regex("""^(\s*)(\d+)\.\s+(.+)""")
private val fenceRegex      = Regex("""^```(\w*)""")
private val hrRegex         = Regex("""^[-*_]{3,}\s*$""")

private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines  = raw.lines()
    var i      = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            // Fenced code block
            fenceRegex.matches(trimmed) -> {
                val lang = fenceRegex.find(trimmed)?.groupValues?.getOrNull(1) ?: ""
                val codeBuf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeBuf.appendLine(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(codeBuf.toString().trimEnd(), lang))
            }

            // Heading
            headingRegex.matches(trimmed) -> {
                val m = headingRegex.find(trimmed)!!
                blocks.add(MarkdownBlock.Heading(m.groupValues[1].length, m.groupValues[2]))
            }

            // Horizontal rule
            hrRegex.matches(trimmed) -> blocks.add(MarkdownBlock.HorizontalRule)

            // Bullet list item
            bulletRegex.matches(line) -> {
                val m = bulletRegex.find(line)!!
                val indent = m.groupValues[1].length / 2
                blocks.add(MarkdownBlock.BulletItem(m.groupValues[2], indent))
            }

            // Ordered list item
            orderedRegex.matches(line) -> {
                val m = orderedRegex.find(line)!!
                val indent = m.groupValues[1].length / 2
                blocks.add(MarkdownBlock.OrderedItem(m.groupValues[2].toInt(), m.groupValues[3], indent))
            }

            // Blank line — ignored (paragraph separator)
            trimmed.isEmpty() -> { /* skip */ }

            // Paragraph — collect consecutive non-special lines
            else -> {
                val paraBuf = StringBuilder(line)
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    val nextTrimmed = next.trim()
                    if (nextTrimmed.isEmpty() ||
                        headingRegex.matches(nextTrimmed) ||
                        fenceRegex.matches(nextTrimmed) ||
                        hrRegex.matches(nextTrimmed) ||
                        bulletRegex.matches(next) ||
                        orderedRegex.matches(next)) break
                    paraBuf.append(' ').append(next.trim())
                    i++
                }
                blocks.add(MarkdownBlock.Paragraph(paraBuf.toString()))
                continue
            }
        }
        i++
    }
    return blocks
}

// ─── Inline formatter ─────────────────────────────────────────────────────────

private fun buildInlineAnnotated(text: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Inline code: `...`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(
                            fontFamily  = FontFamily.Monospace,
                            background  = Surface3,
                            color       = TealLight,
                            fontSize    = 13.sp
                        )) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Bold+italic: ***...***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else { append(text[i]); i++ }
                }
                // Bold: **...** or __...__
                (text.startsWith("**", i) || text.startsWith("__", i)) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Italic: *...* or _..._
                (text[i] == '*' || text[i] == '_') -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Strikethrough: ~~...~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}
