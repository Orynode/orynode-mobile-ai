package ai.orynode.mobile.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object OrynodeColors {
    val paper = Color(0xFFF6F8FD)
    val paperDeep = Color(0xFFE8ECF6)
    val ink = Color(0xFF12182A)
    val inkSoft = ink.copy(alpha = 0.62f)
    val inkFaint = ink.copy(alpha = 0.38f)
    val accent = Color(0xFF3B7BEA)
    val accentSoft = accent.copy(alpha = 0.12f)
    val brandCyan = Color(0xFF2BB4F0)
    val brandBlue = Color(0xFF3B7BEA)
    val brandIndigo = Color(0xFF5B5FE8)
    val brandViolet = Color(0xFF7A3FD4)
    val rule = ink.copy(alpha = 0.10f)
    val caution = Color(0xFFB05338)
    val cautionFill = caution.copy(alpha = 0.08f)
    val readyGreen = Color(0xFF2E7D4F)

    val brandGradient = Brush.linearGradient(
        colors = listOf(brandCyan, brandBlue, brandIndigo, brandViolet),
        start = Offset.Zero,
        end = Offset(900f, 900f),
    )

    val brandGradientHorizontal = Brush.horizontalGradient(
        colors = listOf(brandCyan, brandBlue, brandViolet),
    )
}

@Composable
fun PaperBackground(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OrynodeColors.paper),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            OrynodeColors.brandCyan.copy(alpha = 0.10f),
                            Color.Transparent,
                            OrynodeColors.brandViolet.copy(alpha = 0.08f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            OrynodeColors.brandBlue.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = Offset(Float.POSITIVE_INFINITY, 0f),
                        radius = 900f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            OrynodeColors.brandViolet.copy(alpha = 0.07f),
                            Color.Transparent,
                        ),
                        center = Offset(0f, Float.POSITIVE_INFINITY),
                        radius = 760f,
                    ),
                ),
        )
        content()
    }
}

@Composable
fun PrimaryBrandButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.background(brush = OrynodeColors.brandGradient, shape = shape)
                } else {
                    Modifier.background(
                        color = OrynodeColors.brandBlue.copy(alpha = 0.40f),
                        shape = shape,
                    )
                },
            )
            .clip(shape)
            .then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.55f),
        )
    }
}

@Composable
fun SecondaryBrandButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(OrynodeColors.paperDeep, shape)
            .border(1.dp, OrynodeColors.rule, shape)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = OrynodeColors.ink)
    }
}

@Composable
fun AccentTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, color = OrynodeColors.accent, fontWeight = FontWeight.SemiBold)
    }
}
