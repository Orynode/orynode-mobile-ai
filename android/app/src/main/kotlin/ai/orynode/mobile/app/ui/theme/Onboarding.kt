package ai.orynode.mobile.app.ui.theme

import ai.orynode.mobile.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.brand_logo),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Shared identity: logo + name + 本地AI知识库 (same on cover and welcome). */
@Composable
fun OnboardingBrandHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo(modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Orynode Mobile AI",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrynodeColors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "本地AI知识库",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrynodeColors.accent,
            letterSpacing = 2.sp,
        )
    }
}

/**
 * Cover and welcome share one vertical composition (aligned with iOS).
 * Brand stays optically centered; footer height is always reserved so the mark does not jump.
 */
@Composable
fun OnboardingStageLayout(
    statusMessage: String?,
    showsProgress: Boolean,
    footerHeight: Dp = 120.dp,
    footer: @Composable () -> Unit,
) {
    PaperBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
        ) {
            Spacer(modifier = Modifier.weight(1f))
            OnboardingBrandHeader()
            // Match iOS OnboardingStatusSlot: 18dp top padding outside a 64dp content slot
            // (padding inside 64dp clipped the status line — spinner only, no copy).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .height(64.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (showsProgress) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = OrynodeColors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            statusMessage ?: "请稍候…",
                            fontSize = 14.sp,
                            color = OrynodeColors.inkSoft,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(footerHeight)
                    .padding(bottom = 28.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                footer()
            }
        }
    }
}
