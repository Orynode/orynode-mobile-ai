package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.ui.components.DocumentTypeBadge
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.app.ui.theme.PrimaryBrandButton
import ai.orynode.mobile.app.ui.theme.SecondaryBrandButton
import ai.orynode.mobile.domain.KnowledgeDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KnowledgeHomeContent(
    viewModel: KnowledgeBaseViewModel,
    state: KnowledgeUiState,
    onOpenChat: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importDocument(uri, null)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "本机知识库",
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                color = OrynodeColors.ink,
            )
            Text(
                "导入你的资料，在设备上检索、提问并核对来源。",
                fontSize = 17.sp,
                color = OrynodeColors.inkSoft,
            )
            Text(
                "知识库路径本机处理 · 不上传 · 无云端补答",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OrynodeColors.accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryBrandButton(
                text = if (state.isImporting) "正在建立索引…" else "导入资料",
                enabled = !state.isImporting && !state.isAnswering,
            ) {
                viewModel.prepareForImport()
                picker.launch(
                    arrayOf(
                        "text/plain",
                        "text/markdown",
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "*/*",
                    ),
                )
            }
            SecondaryBrandButton(
                text = "向知识库提问",
                enabled = viewModel.readyDocumentCount > 0 && !state.isImporting && !state.isAnswering,
                onClick = {
                    viewModel.startNewChat()
                    onOpenChat()
                },
            )
            Text(
                if (viewModel.readyDocumentCount == 0) "导入完成后即可开始提问"
                else "${viewModel.readyDocumentCount} 份资料可用于回答",
                fontSize = 12.sp,
                color = OrynodeColors.inkFaint,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "我的文档",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OrynodeColors.inkFaint,
                letterSpacing = 1.sp,
            )
            when {
                state.isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = OrynodeColors.accent,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在读取文档…", color = OrynodeColors.inkSoft, fontSize = 14.sp)
                    }
                }
                state.documents.isEmpty() -> {
                    Text(
                        "还没有文档。资料只保存在这台设备上。",
                        fontSize = 14.sp,
                        color = OrynodeColors.inkSoft,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                else -> state.documents.forEach { document ->
                    DocumentRow(
                        document = document,
                        onPreview = { viewModel.openDocumentPreview(document) },
                        onRetry = { viewModel.retry(document) },
                        onDelete = { viewModel.delete(document) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(
    document: KnowledgeDocument,
    onPreview: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, OrynodeColors.rule, RoundedCornerShape(17.dp))
            .clickable(onClick = onPreview)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentTypeBadge(document.sourcePath.substringAfterLast('/'), size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                document.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = OrynodeColors.ink,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(3.dp))
            DocumentStatus(document)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = OrynodeColors.inkSoft)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("预览") },
                    onClick = {
                        menuOpen = false
                        onPreview()
                    },
                )
                if (document.state == KnowledgeDocument.State.Failed) {
                    DropdownMenuItem(
                        text = { Text("重试索引") },
                        onClick = {
                            menuOpen = false
                            onRetry()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除", color = OrynodeColors.caution) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun DocumentStatus(document: KnowledgeDocument) {
    val (label, icon, color) = when (document.state) {
        KnowledgeDocument.State.Importing -> Triple("正在索引", Icons.Default.Sync, OrynodeColors.accent)
        KnowledgeDocument.State.Ready -> Triple("已索引", Icons.Default.CheckCircle, OrynodeColors.readyGreen)
        KnowledgeDocument.State.Failed -> Triple(
            document.errorMessage ?: "失败",
            Icons.Default.Error,
            OrynodeColors.caution,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1)
        if (document.state == KnowledgeDocument.State.Ready && document.importedChunkCount > 0) {
            Text(
                " · ${document.importedChunkCount} chunks",
                fontSize = 12.sp,
                color = OrynodeColors.inkFaint,
                maxLines = 1,
            )
        }
    }
}
