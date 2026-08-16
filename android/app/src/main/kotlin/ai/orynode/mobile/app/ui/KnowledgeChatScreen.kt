package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.ui.chat.KnowledgeChatTurn
import ai.orynode.mobile.app.ui.components.ChatBubble
import ai.orynode.mobile.app.ui.components.DocumentTypeBadge
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.application.DocumentDisplayName
import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeSearchScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeChatScreen(
    viewModel: KnowledgeBaseViewModel,
    state: KnowledgeUiState,
    onBack: () -> Unit,
) {
    // TextFieldValue keeps IME composition (拼音选字); plain String often drops CJK composing text.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var showsScopePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = draft.text.trim().isNotEmpty() && !state.isAnswering && !state.isImporting

    fun sendDraft() {
        val q = draft.text.trim()
        if (q.isEmpty() || state.isAnswering || state.isImporting) return
        draft = TextFieldValue("")
        keyboard?.hide()
        viewModel.ask(q)
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text, state.isAnswering) {
        val last = state.messages.lastIndex
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        viewModel.activeSessionTitle,
                        color = OrynodeColors.ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                    )
                },
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
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Spacer(modifier = Modifier.height(12.dp)) }
                if (state.messages.isEmpty()) {
                    item { ChatEmptyState() }
                }
                items(state.messages, key = { it.id }) { message ->
                    if (message.role == KnowledgeChatTurn.Role.User || message.text.isNotEmpty()) {
                        MessageBubble(
                            message = message,
                            documentsById = state.documents.associateBy { it.id },
                            onCitationClick = viewModel::openCitationPreview,
                        )
                    }
                }
                if (state.isAnswering && state.messages.lastOrNull()?.text.isNullOrEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = OrynodeColors.accent,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                state.answeringPhase ?: "正在检索本机资料…",
                                fontSize = 14.sp,
                                color = OrynodeColors.inkSoft,
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
            ComposerBar(
                draft = draft,
                onDraftChange = { draft = it },
                canSend = canSend,
                answering = state.isAnswering,
                scope = state.searchScope,
                documents = state.documents,
                onPickScope = { showsScopePicker = true },
                onClearScope = { viewModel.setSearchScope(KnowledgeSearchScope.All) },
                onSend = ::sendDraft,
            )
        }
    }

    if (showsScopePicker) {
        ScopePickerSheet(
            documents = state.documents.filter { it.state == KnowledgeDocument.State.Ready },
            scope = state.searchScope,
            onDismiss = { showsScopePicker = false },
            onConfirm = {
                viewModel.setSearchScope(it)
                showsScopePicker = false
            },
        )
    }
}

@Composable
private fun MessageBubble(
    message: KnowledgeChatTurn,
    documentsById: Map<java.util.UUID, KnowledgeDocument>,
    onCitationClick: (KnowledgeCitation) -> Unit,
) {
    val isUser = message.role == KnowledgeChatTurn.Role.User
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatBubble(isUser = isUser) {
            if (isUser) {
                Text(
                    message.text,
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                )
            } else {
                CitedAnswerText(
                    text = message.text,
                    citations = message.citations,
                    isUser = false,
                    onSelect = onCitationClick,
                )
            }
        }
        if (!isUser && message.citations.isNotEmpty()) {
            CitationSourcesSection(
                citations = message.citations,
                titleFor = { citation ->
                    documentsById[citation.documentId]?.title
                        ?: DocumentDisplayName.fromFileName(citation.documentTitle, citation.documentId)
                },
                fileNameFor = { citation ->
                    documentsById[citation.documentId]?.sourcePath?.substringAfterLast('/')
                        ?: citation.documentTitle
                },
                onSelect = onCitationClick,
            )
        }
    }
}

@Composable
private fun ChatEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, OrynodeColors.rule, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = OrynodeColors.accent,
            modifier = Modifier.size(28.dp),
        )
        Text(
            "向本机资料提问",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrynodeColors.ink,
            letterSpacing = (-0.3).sp,
        )
        Text(
            "回答只会依据已索引文档，并附上可核对的来源。",
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = OrynodeColors.inkSoft,
        )
    }
}

@Composable
private fun ComposerBar(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    canSend: Boolean,
    answering: Boolean,
    scope: KnowledgeSearchScope,
    documents: List<KnowledgeDocument>,
    onPickScope: () -> Unit,
    onClearScope: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OrynodeColors.paper.copy(alpha = 0.97f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(OrynodeColors.rule))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (scope is KnowledgeSearchScope.Documents) {
                ScopeChip(scope = scope, documents = documents, onClear = onClearScope, onClick = onPickScope)
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.78f))
                        .border(1.dp, OrynodeColors.rule, CircleShape)
                        .clickable(enabled = !answering, onClick = onPickScope),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "选择检索资料",
                        tint = OrynodeColors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                val fieldShape = RoundedCornerShape(18.dp)
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(fieldShape)
                        .background(Color.White.copy(alpha = 0.78f), fieldShape)
                        .border(1.dp, OrynodeColors.rule, fieldShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    enabled = !answering,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = OrynodeColors.ink,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(OrynodeColors.accent),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.text.isEmpty()) {
                                Text(
                                    if (scope is KnowledgeSearchScope.Documents) "向已选资料提问…"
                                    else "向本机资料提问…",
                                    color = OrynodeColors.inkFaint,
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) OrynodeColors.brandGradient
                            else Brush.linearGradient(
                                listOf(
                                    OrynodeColors.brandBlue.copy(alpha = 0.35f),
                                    OrynodeColors.brandBlue.copy(alpha = 0.35f),
                                ),
                            ),
                        )
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "发送",
                        tint = Color.White.copy(alpha = if (canSend) 1f else 0.7f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(
    scope: KnowledgeSearchScope.Documents,
    documents: List<KnowledgeDocument>,
    onClear: () -> Unit,
    onClick: () -> Unit,
) {
    val selected = documents.filter { scope.ids.contains(it.id) }
    val label = when {
        selected.size == 1 -> selected.first().title
        else -> "${selected.size} 份资料"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(OrynodeColors.accent.copy(alpha = 0.10f))
            .border(1.dp, OrynodeColors.accent.copy(alpha = 0.22f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = OrynodeColors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        IconButton(onClick = onClear, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.Close, contentDescription = "清除资料范围", tint = OrynodeColors.inkFaint, modifier = Modifier.size(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopePickerSheet(
    documents: List<KnowledgeDocument>,
    scope: KnowledgeSearchScope,
    onDismiss: () -> Unit,
    onConfirm: (KnowledgeSearchScope) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember {
        mutableStateOf(
            when (scope) {
                KnowledgeSearchScope.All -> emptySet()
                is KnowledgeSearchScope.Documents -> scope.ids
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrynodeColors.paper,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("选择检索资料", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OrynodeColors.ink)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { selected = emptySet() }) {
                Text("全部资料", color = OrynodeColors.accent)
            }
            documents.forEach { document ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (selected.contains(document.id)) selected - document.id
                            else selected + document.id
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selected.contains(document.id),
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + document.id else selected - document.id
                        },
                    )
                    DocumentTypeBadge(document.sourcePath.substringAfterLast('/'))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(document.title, color = OrynodeColors.ink, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    onConfirm(
                        if (selected.isEmpty()) KnowledgeSearchScope.All
                        else KnowledgeSearchScope.Documents(selected),
                    )
                },
            ) {
                Text("完成", color = OrynodeColors.accent, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
