package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.serving.GeneratorRuntimeState
import ai.orynode.mobile.app.ui.theme.OnboardingStageLayout
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.app.ui.theme.PrimaryBrandButton
import ai.orynode.mobile.app.ui.theme.SecondaryBrandButton
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun LaunchScreen(appViewModel: AppViewModel) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    OnboardingStageLayout(
        statusMessage = state.prepStatusMessage,
        showsProgress = state.isPreparingModel,
    ) {}
}

@Composable
fun ModelSetupScreen(appViewModel: AppViewModel) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val preparing = state.isPreparingModel
    val downloading = state.isDownloadingModel
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        // Picker is selection only; work starts after a file is chosen.
        if (uri != null) appViewModel.importAndLoadModel(uri)
    }

    val footerHeight = when {
        downloading -> 180.dp
        preparing -> 120.dp
        state.runtimeState is GeneratorRuntimeState.Installed && state.installed != null -> 200.dp
        else -> 160.dp
    }

    OnboardingStageLayout(
        statusMessage = state.prepStatusMessage,
        showsProgress = preparing && !downloading,
        footerHeight = footerHeight,
    ) {
        when {
            preparing -> Unit
            downloading -> DownloadProgressFooter(
                progress = state.downloadProgress,
                onCancel = appViewModel::cancelModelDownload,
            )
            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.runtimeState is GeneratorRuntimeState.Installed && state.installed != null) {
                        Text(
                            state.installed!!.storageDescription,
                            fontSize = 13.sp,
                            color = OrynodeColors.inkSoft,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PrimaryBrandButton("继续使用") { appViewModel.loadInstalledModel() }
                        Spacer(modifier = Modifier.height(12.dp))
                        SecondaryBrandButton("更换模型") {
                            modelPicker.launch(arrayOf("*/*"))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        SecondaryBrandButton("下载模型 (约2.6G · 需网络)") {
                            appViewModel.downloadAndLoadModel()
                        }
                    } else {
                        PrimaryBrandButton("导入模型 (Gemma .litertlm)") {
                            modelPicker.launch(arrayOf("*/*"))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        SecondaryBrandButton("下载模型 (约2.6G · 需网络)") {
                            appViewModel.downloadAndLoadModel()
                        }
                    }
                }
            }
        }
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = appViewModel::clearError,
            confirmButton = {
                TextButton(onClick = appViewModel::clearError) {
                    Text("好", color = OrynodeColors.accent)
                }
            },
            title = { Text("模型准备失败") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun DownloadProgressFooter(
    progress: ModelDownloadUiProgress?,
    onCancel: () -> Unit,
) {
    val received = progress?.bytesReceived ?: 0L
    val total = progress?.totalBytes
    val fraction = if (total != null && total > 0L) {
        (received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DeterminateDownloadBar(fraction = fraction)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            downloadStatusLabel(received, total, progress?.bytesPerSecond),
            fontSize = 13.sp,
            color = OrynodeColors.inkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SecondaryBrandButton("取消") { onCancel() }
    }
}

/** Literal width fill — avoids Material3 stop-indicator / gap making low % look fuller. */
@Composable
private fun DeterminateDownloadBar(fraction: Float?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(OrynodeColors.rule),
    ) {
        if (fraction != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(OrynodeColors.accent),
            )
        } else {
            // Unknown total: thin indeterminate pulse via Material track only.
            LinearProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = OrynodeColors.accent,
                trackColor = Color.Transparent,
            )
        }
    }
}

private fun downloadStatusLabel(
    received: Long,
    total: Long?,
    bytesPerSecond: Long?,
): String {
    val receivedGb = received / 1_000_000_000.0
    val speed = formatDownloadSpeed(bytesPerSecond)
    val base = if (total != null && total > 0L) {
        val totalGb = total / 1_000_000_000.0
        val percent = ((received.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        String.format(
            Locale.US,
            "正在下载… %.2f / %.2f GB（%d%%）",
            receivedGb,
            totalGb,
            percent,
        )
    } else {
        String.format(Locale.US, "正在下载… %.2f GB", receivedGb)
    }
    return if (speed != null) "$base\n$speed" else base
}

private fun formatDownloadSpeed(bytesPerSecond: Long?): String? {
    if (bytesPerSecond == null || bytesPerSecond <= 0L) return null
    val value = when {
        bytesPerSecond >= 1_000_000L ->
            String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
        bytesPerSecond >= 1_000L ->
            String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1_000.0)
        else -> "$bytesPerSecond B/s"
    }
    return "下载速度：$value"
}
