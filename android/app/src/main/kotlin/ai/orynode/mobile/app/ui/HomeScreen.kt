package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.ui.theme.BrandLogo
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.app.ui.theme.PaperBackground
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appViewModel: AppViewModel,
    knowledgeViewModel: KnowledgeBaseViewModel,
    onOpenChat: () -> Unit,
) {
    val kbState by knowledgeViewModel.state.collectAsStateWithLifecycle()

    PaperBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandLogo(modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(7.dp))
                                Text(
                                    "Orynode",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OrynodeColors.ink,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = knowledgeViewModel::openHistory) {
                                Icon(Icons.Default.History, contentDescription = "历史对话", tint = OrynodeColors.ink)
                            }
                            IconButton(onClick = appViewModel::openSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "设置", tint = OrynodeColors.ink)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    KnowledgeHomeContent(
                        viewModel = knowledgeViewModel,
                        state = kbState,
                        onOpenChat = onOpenChat,
                    )
                }
            }

            if (kbState.showsHistory) {
                KnowledgeChatHistoryDrawer(
                    sessions = kbState.sessions,
                    activeSessionId = kbState.activeSessionId,
                    onClose = knowledgeViewModel::closeHistory,
                    onNewChat = {
                        knowledgeViewModel.startNewChat()
                        onOpenChat()
                    },
                    onSelect = { id ->
                        knowledgeViewModel.openSession(id)
                        onOpenChat()
                    },
                    onDelete = knowledgeViewModel::deleteSession,
                )
            }
        }
    }

    kbState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = knowledgeViewModel::clearError,
            confirmButton = {
                TextButton(onClick = knowledgeViewModel::clearError) {
                    Text("好", color = OrynodeColors.accent)
                }
            },
            title = { Text("知识库暂时不可用") },
            text = { Text(message) },
        )
    }
}
