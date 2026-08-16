package ai.orynode.mobile.app.ui.components

import ai.orynode.mobile.R
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Messages-style bubble matching iOS [ChatBubble]:
 * asymmetric tip corner (6dp) + opposite side reserved ≥52dp.
 */
@Composable
fun ChatBubble(
    isUser: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isUser) 52.dp else 0.dp,
                end = if (isUser) 0.dp else 52.dp,
            ),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val shape = if (isUser) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        } else {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
        }
        Box(
            modifier = Modifier
                .clip(shape)
                .then(
                    if (isUser) {
                        Modifier.background(OrynodeColors.brandGradient, shape)
                    } else {
                        // No stroke: Compose `.copy(alpha=0.9)` on `rule` replaced alpha and
                        // produced a near-black border (iOS multiplies opacity instead).
                        Modifier.background(Color.White.copy(alpha = 0.90f), shape)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            content()
        }
    }
}

/**
 * File-type icon matching iOS `KnowledgeDocumentTypeIcon`
 * (same Material Icon Theme PNG assets).
 */
@Composable
fun DocumentTypeBadge(fileName: String, size: Dp = 28.dp) {
    val kind = KnowledgeDocumentKind.fromFileName(fileName)
    Image(
        painter = painterResource(kind.drawableRes),
        contentDescription = kind.contentDescription,
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/** Same mapping as iOS `KnowledgeDocumentKind`. */
private enum class KnowledgeDocumentKind(
    @param:DrawableRes val drawableRes: Int,
    val contentDescription: String,
) {
    PlainText(R.drawable.file_icon_text, "纯文本文档"),
    Markdown(R.drawable.file_icon_markdown, "Markdown 文档"),
    Pdf(R.drawable.file_icon_pdf, "PDF 文档"),
    Word(R.drawable.file_icon_word, "Word 文档"),
    Spreadsheet(R.drawable.file_icon_excel, "Excel 文档"),
    Presentation(R.drawable.file_icon_powerpoint, "PowerPoint 文档"),
    Unknown(R.drawable.file_icon_text, "文档"),
    ;

    companion object {
        fun fromFileName(fileName: String): KnowledgeDocumentKind {
            val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return when (ext) {
                "txt", "text" -> PlainText
                "md", "markdown" -> Markdown
                "pdf" -> Pdf
                "docx", "docm" -> Word
                "xlsx", "xlsm" -> Spreadsheet
                "pptx", "pptm" -> Presentation
                else -> Unknown
            }
        }
    }
}

@Composable
fun SettingRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = OrynodeColors.ink, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(value, color = OrynodeColors.inkSoft, fontSize = 15.sp)
    }
}

@Composable
fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            title.uppercase(),
            color = OrynodeColors.inkFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.72f))
                .border(1.dp, OrynodeColors.rule, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            content()
        }
        if (footer != null) {
            Text(
                footer,
                color = OrynodeColors.inkFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
