package ai.orynode.mobile.app.ui

import ai.orynode.mobile.BuildConfig
import ai.orynode.mobile.app.serving.GeneratorRuntimeState
import ai.orynode.mobile.app.ui.components.SettingRow
import ai.orynode.mobile.app.ui.components.SettingsSection
import ai.orynode.mobile.app.ui.theme.AccentTextButton
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.app.ui.theme.PaperBackground
import ai.orynode.mobile.domain.KnowledgeBaseLimits
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    knowledgeViewModel: KnowledgeBaseViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appState by appViewModel.state.collectAsStateWithLifecycle()
    val kbState by knowledgeViewModel.state.collectAsStateWithLifecycle()
    var confirmsDeleteModel by remember { mutableStateOf(false) }
    var confirmsClearHistory by remember { mutableStateOf(false) }
    var showsOpenSource by remember { mutableStateOf(false) }
    var knowledgeBytes by remember { mutableLongStateOf(0L) }
    var chatBytes by remember { mutableLongStateOf(0L) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) appViewModel.importAndLoadModel(uri)
    }

    LaunchedEffect(Unit) {
        knowledgeViewModel.refresh()
        knowledgeViewModel.loadChatHistory()
        appViewModel.refreshRuntime()
        knowledgeBytes = LocalDataFootprint.knowledgeBaseByteCount(context)
        chatBytes = LocalDataFootprint.chatHistoryByteCount(context)
    }

    if (showsOpenSource) {
        OpenSourceNoticesScreen(onBack = { showsOpenSource = false })
        return
    }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("设置", color = OrynodeColors.ink, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        AccentTextButton("完成", onClick = onClose)
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
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                SettingsSection(
                    title = "本地模型",
                    footer = "资料检索和回答生成都在本机完成。释放内存不会删除磁盘上的模型文件。",
                ) {
                    SettingRow("状态", modelStatusText(appState))
                    SettingRow("占用空间", appState.installed?.storageDescription ?: "尚未安装")
                    appState.noticeMessage?.let { notice ->
                        Text(
                            notice,
                            fontSize = 13.sp,
                            color = OrynodeColors.inkSoft,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    when {
                        appState.isPreparingModel -> Unit
                        appState.runtimeState is GeneratorRuntimeState.Ready ->
                            AccentTextButton("释放内存中的模型") { appViewModel.unloadLoadedModel() }
                        appState.runtimeState is GeneratorRuntimeState.Installed ->
                            AccentTextButton("重新加载模型") { appViewModel.loadInstalledModel() }
                    }
                    if (appState.installed == null) {
                        AccentTextButton("导入模型") {
                            modelPicker.launch(arrayOf("*/*"))
                        }
                    }
                }

                SettingsSection(
                    title = "知识库",
                    footer = "占用含源文件、SQLite 索引与向量。上限 ${KnowledgeBaseLimits.MAX_CHUNKS} chunks。",
                ) {
                    SettingRow("文档", "${kbState.documents.size} 篇")
                    SettingRow(
                        "索引用量",
                        "${kbState.indexedChunkCount} / ${KnowledgeBaseLimits.MAX_CHUNKS} chunks",
                    )
                    SettingRow("知识库占用", LocalDataFootprint.formattedByteCount(knowledgeBytes))
                }

                SettingsSection(
                    title = "数据",
                    footer = "聊天记录只保存在这台手机，不会上传。",
                ) {
                    SettingRow(
                        "聊天记录",
                        historySummaryText(
                            count = knowledgeViewModel.persistedSessionCount,
                            bytes = chatBytes,
                        ),
                    )
                    if (knowledgeViewModel.persistedSessionCount > 0) {
                        TextButton(onClick = { confirmsClearHistory = true }) {
                            Text("清空聊天记录", color = OrynodeColors.caution)
                        }
                    }
                }

                SettingsSection(title = "隐私") {
                    Text(
                        "导入文档、索引、向量与回答只保存在这台手机。不会创建账号，也不会同步云端。" +
                            "模型权重可在准备页经镜像下载；进入知识库导入/索引/检索/生成路径后不联网。",
                        fontSize = 14.sp,
                        color = OrynodeColors.inkSoft,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }

                SettingsSection(
                    title = "关于",
                    footer = "源码托管在 GitHub（MIT）。打开链接会离开本应用并使用系统浏览器。" +
                        "当前为 Spike（versionName 含 -spike）；真机闸门见仓库 android/docs/verification.md。",
                ) {
                    SettingRow("产品", "Orynode Mobile AI")
                    SettingRow("版本", BuildConfig.VERSION_NAME)
                    SettingRow("检索向量", appState.embeddingBackendLabel)
                    if (appState.embeddingBackendLabel.contains("fallback", ignoreCase = true)) {
                        Text(
                            "当前为 Debug hash 回退：语义检索已降级。请运行 prepare-embedding-model.sh 后重装 Debug/Release。",
                            fontSize = 13.sp,
                            color = OrynodeColors.caution,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    SettingRow("模型", "Gemma 4 E2B")
                    SettingRow("引擎", "LiteRT-LM")
                    SettingRow("许可证", OrynodeOpenSource.licenseName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(OrynodeOpenSource.repositoryUrl)),
                                )
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("源码", color = OrynodeColors.ink, fontSize = 16.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            OrynodeOpenSource.repositoryName,
                            color = OrynodeColors.accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showsOpenSource = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("开源许可", color = OrynodeColors.ink, fontSize = 16.sp)
                    }
                }

                if (appState.installed != null) {
                    SettingsSection(title = "危险操作", footer = "删除后需要重新导入模型才能继续问答。") {
                        TextButton(onClick = { confirmsDeleteModel = true }) {
                            Text("删除本机模型", color = OrynodeColors.caution)
                        }
                    }
                }
            }
        }
    }

    if (confirmsDeleteModel) {
        AlertDialog(
            onDismissRequest = { confirmsDeleteModel = false },
            title = { Text("删除本机模型？") },
            text = {
                Text("将删除本机模型（当前 ${appState.installed?.storageDescription ?: "未知"}），并退出到模型准备页。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmsDeleteModel = false
                    appViewModel.deleteInstalledModel()
                }) { Text("删除模型", color = OrynodeColors.caution) }
            },
            dismissButton = {
                TextButton(onClick = { confirmsDeleteModel = false }) { Text("取消") }
            },
        )
    }
    if (confirmsClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmsClearHistory = false },
            title = { Text("清空全部聊天记录？") },
            text = { Text("将删除本机保存的全部对话，文档与索引不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmsClearHistory = false
                    knowledgeViewModel.clearAllChatHistory()
                    chatBytes = LocalDataFootprint.chatHistoryByteCount(context)
                }) { Text("清空聊天记录", color = OrynodeColors.caution) }
            },
            dismissButton = {
                TextButton(onClick = { confirmsClearHistory = false }) { Text("取消") }
            },
        )
    }
}

private fun historySummaryText(count: Int, bytes: Long): String {
    val size = LocalDataFootprint.formattedByteCount(bytes)
    return if (count == 0) "无" else "$count 条 · $size"
}

private fun modelStatusText(state: AppUiState): String = when (state.prepPhase) {
    ModelPrepPhase.Importing -> "导入中"
    ModelPrepPhase.LoadingEngine -> "加载中"
    ModelPrepPhase.Checking -> "检查中"
    ModelPrepPhase.Idle -> when (state.runtimeState) {
        GeneratorRuntimeState.Ready -> "已就绪"
        GeneratorRuntimeState.Loading -> "加载中"
        GeneratorRuntimeState.Installed -> "已安装，待加载"
        is GeneratorRuntimeState.Failed -> "加载失败"
        GeneratorRuntimeState.NotInstalled -> "未安装"
    }
}
