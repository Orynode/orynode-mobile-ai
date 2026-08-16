package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.app.ui.theme.PaperBackground
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceNoticesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val noticeText = remember {
        runCatching {
            context.assets.open("NOTICE").bufferedReader().use { it.readText() }
        }.getOrDefault(
            "未能加载随附 NOTICE。请参阅仓库中的 android/NOTICE 与根目录 LICENSE（MIT）。",
        )
    }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("开源许可", color = OrynodeColors.ink, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OrynodeColors.ink)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OrynodeColors.paperDeep.copy(alpha = 0.55f))
                        .padding(16.dp),
                ) {
                    Text("本应用源码", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OrynodeColors.inkSoft)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(OrynodeOpenSource.repositoryUrl)),
                        )
                    }) {
                        Text(OrynodeOpenSource.repositoryName, color = OrynodeColors.accent, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        "许可证：${OrynodeOpenSource.licenseName}",
                        fontSize = 13.sp,
                        color = OrynodeColors.inkSoft,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    noticeText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = OrynodeColors.ink,
                )
            }
        }
    }
}
