package com.abrah.nightmare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R

/**
 * ⭐⭐ The agent's Markdown, rendered (Violet Studio: "never display raw ** markers") — the
 * subset chat models write: headings, paragraphs, **bold**, *italic*, `inline code`, links,
 * - / * / 1. lists and fenced code blocks (which scroll sideways and offer Copy).
 * ⚠ Small and forgiving on purpose: anything it does not recognise is shown as the text it is.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in markdownBlocks(markdown)) {
            when (block) {
                is MdBlock.Code -> CodeBlock(block.text)
                is MdBlock.Heading -> Text(
                    inline(block.text, color),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.labelLarge
                    },
                    color = color,
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 12).dp)) {
                    Text(block.marker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.width(6.dp))
                    Text(inline(block.text, color), style = MaterialTheme.typography.bodyMedium, color = color)
                }
                is MdBlock.Para -> Text(inline(block.text, color), style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(36.dp)) {
                Icon(CopyIcon, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            code, style = MeasureTextStyle, color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

internal sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Code(val text: String) : MdBlock
}

/** ⭐ The blocks of [md] — pure, so it is tested without a screen. */
internal fun markdownBlocks(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
        para.clear()
    }
    val lines = md.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out += MdBlock.Code(code.toString().trimEnd('\n'))
            }
            Regex("^#{1,6} ").containsMatchIn(t) -> {
                flush()
                val level = t.takeWhile { it == '#' }.length
                out += MdBlock.Heading(level, t.drop(level).trim())
            }
            Regex("^[-*•] ").containsMatchIn(t) -> {
                flush()
                out += MdBlock.Item("•", t.drop(2).trim(), (line.length - t.length) / 2)
            }
            Regex("^\\d+[.)] ").containsMatchIn(t) -> {
                flush()
                val n = t.takeWhile { it.isDigit() }
                out += MdBlock.Item("$n.", t.substringAfter(' ').trim(), (line.length - t.length) / 2)
            }
            t.isEmpty() -> flush()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
        i++
    }
    flush()
    return out
}

/** ⭐ **bold**, *italic* / _italic_, `code` and [links](url) inside one block. */
@Composable
private fun inline(text: String, color: Color): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val link = Accent
    return remember(text, color) { inlineOf(text, codeBg, link) }
}

@Composable
private fun <T> remember(a: Any, b: Any, f: () -> T): T = androidx.compose.runtime.remember(a, b, f)

internal fun inlineOf(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    val token = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")
    var at = 0
    for (m in token.findAll(text)) {
        append(text.substring(at, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[3]) }
            g[4].isNotEmpty() -> withLink(LinkAnnotation.Url(g[5], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[4]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[7]) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

/** ⭐ A message's copy action — the person's and the agent's alike. */
@Composable
fun CopyAction(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = modifier.size(32.dp)) {
        Icon(CopyIcon, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("unused")
private val alignEnd = Alignment.End
