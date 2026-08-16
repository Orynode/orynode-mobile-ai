package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.ui.components.DocumentTypeBadge
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.domain.KnowledgeCitation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders assistant answers with light Markdown and closed-set `[n]` citation chips.
 * Line breaks follow the model text: each non-empty line is its own block (iOS parity).
 */
@Composable
fun CitedAnswerText(
    text: String,
    citations: List<KnowledgeCitation>,
    isUser: Boolean = false,
    onSelect: (KnowledgeCitation) -> Unit,
) {
    val citationByIndex = remember(citations) {
        citations.associateBy { it.index }
    }
    val paragraphs = remember(text) { displayParagraphs(text) }
    val prose = if (isUser) Color.White else OrynodeColors.ink
    val chip = if (isUser) Color.White else OrynodeColors.accent
    val chipBg = if (isUser) Color.White.copy(alpha = 0.22f) else OrynodeColors.accent.copy(alpha = 0.14f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paragraphs.forEach { paragraph ->
            val annotated = remember(paragraph, citationByIndex, prose, chip, chipBg) {
                annotatedParagraph(
                    paragraph = paragraph,
                    citationByIndex = citationByIndex,
                    prose = prose,
                    chip = chip,
                    chipBg = chipBg,
                )
            }
            ClickableText(
                text = annotated,
                style = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = prose,
                ),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "cite", start = offset, end = offset)
                        .firstOrNull()
                        ?.let { annotation ->
                            val index = annotation.item.toIntOrNull() ?: return@ClickableText
                            citationByIndex[index]?.let(onSelect)
                        }
                },
            )
        }
    }
}

@Composable
fun CitationSourcesSection(
    citations: List<KnowledgeCitation>,
    titleFor: (KnowledgeCitation) -> String,
    fileNameFor: (KnowledgeCitation) -> String,
    onSelect: (KnowledgeCitation) -> Unit,
) {
    if (citations.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 52.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "来源",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrynodeColors.inkFaint,
            modifier = Modifier.padding(start = 4.dp),
        )
        citations.sortedBy { it.index }.forEach { citation ->
            val label = citation.locatorLabel ?: citation.locator?.shortLabel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.78f))
                    .border(1.dp, OrynodeColors.rule, RoundedCornerShape(12.dp))
                    .clickable { onSelect(citation) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DocumentTypeBadge(fileNameFor(citation), size = 28.dp)
                Text(
                    "[${citation.index}]",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = OrynodeColors.accent,
                    modifier = Modifier.widthIn(min = 28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        titleFor(citation),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = OrynodeColors.ink,
                        maxLines = 1,
                    )
                    if (!label.isNullOrBlank()) {
                        Text(label, fontSize = 11.sp, color = OrynodeColors.inkFaint, maxLines = 1)
                    }
                }
                Text(
                    "›",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OrynodeColors.inkFaint,
                )
            }
        }
    }
}

internal fun displayParagraphs(from: String): List<String> =
    from.replace("\r\n", "\n")
        .split('\n')
        .map { stripTemplateLabels(it.trim()) }
        .filter { it.isNotEmpty() }

private fun stripTemplateLabels(line: String): String {
    val prefixes = listOf(
        "**结论：**", "**结论:**", "结论：", "结论:",
        "**依据：**", "**依据:**", "依据：", "依据:",
    )
    for (prefix in prefixes) {
        if (line.startsWith(prefix)) return line.removePrefix(prefix).trim()
    }
    return line
}

private fun annotatedParagraph(
    paragraph: String,
    citationByIndex: Map<Int, KnowledgeCitation>,
    prose: Color,
    chip: Color,
    chipBg: Color,
) = buildAnnotatedString {
    var i = 0
    while (i < paragraph.length) {
        when {
            paragraph.startsWith("**", i) -> {
                val end = paragraph.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = prose)) {
                        append(paragraph.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    withStyle(SpanStyle(color = prose)) { append(paragraph[i]) }
                    i += 1
                }
            }
            paragraph[i] == '[' -> {
                val close = paragraph.indexOf(']', i)
                if (close > i + 1) {
                    val inner = paragraph.substring(i + 1, close)
                    val index = inner.toIntOrNull()
                    if (index != null && citationByIndex.containsKey(index)) {
                        val start = length
                        withStyle(
                            SpanStyle(
                                color = chip,
                                background = chipBg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textDecoration = TextDecoration.None,
                            ),
                        ) {
                            append(" $index ")
                        }
                        addStringAnnotation(
                            tag = "cite",
                            annotation = index.toString(),
                            start = start,
                            end = length,
                        )
                        i = close + 1
                    } else {
                        withStyle(SpanStyle(color = prose)) { append(paragraph[i]) }
                        i += 1
                    }
                } else {
                    withStyle(SpanStyle(color = prose)) { append(paragraph[i]) }
                    i += 1
                }
            }
            paragraph.startsWith("- ", i) && i == 0 -> {
                withStyle(SpanStyle(color = prose)) { append("• ") }
                i += 2
            }
            else -> {
                withStyle(SpanStyle(color = prose)) { append(paragraph[i]) }
                i += 1
            }
        }
    }
}
