package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.ui.chat.KnowledgeChatSession
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
fun KnowledgeChatHistoryDrawer(
    sessions: List<KnowledgeChatSession>,
    activeSessionId: UUID?,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onSelect: (UUID) -> Unit,
    onDelete: (UUID) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onClose),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.82f)
                .background(OrynodeColors.paper)
                .clickable(enabled = false) {}
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史对话", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = OrynodeColors.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = "新对话", tint = OrynodeColors.accent)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = OrynodeColors.ink)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (sessions.isEmpty()) {
                Text("还没有保存的对话。", color = OrynodeColors.inkSoft, fontSize = 14.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        val active = session.id == activeSessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (active) OrynodeColors.accentSoft
                                    else Color.White.copy(alpha = 0.55f),
                                )
                                .clickable { onSelect(session.id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(session.title, color = OrynodeColors.ink, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(session.preview, color = OrynodeColors.inkFaint, fontSize = 12.sp, maxLines = 2)
                            }
                            IconButton(onClick = { onDelete(session.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = OrynodeColors.inkFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}
