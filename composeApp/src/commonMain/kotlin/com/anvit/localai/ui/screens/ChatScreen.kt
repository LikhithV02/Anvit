@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.anvit.localai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvit.localai.data.db.entities.ChatSessionEntity
import com.anvit.localai.data.db.entities.CollectionEntity
import com.anvit.localai.ui.components.AgentStepsPanel
import com.anvit.localai.ui.components.MarkdownText
import com.anvit.localai.ui.theme.*
import com.anvit.localai.ui.viewmodels.ChatMessage
import com.anvit.localai.ui.viewmodels.ChatViewModel
import com.anvit.localai.ui.viewmodels.SourceChunk
import com.anvit.localai.utils.currentTimeMillis
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.koin.compose.viewmodel.koinViewModel

// ── Root screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = koinViewModel()) {
    val uiState    by viewModel.uiState.collectAsState()
    val listState   = rememberLazyListState()
    val scope       = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var showCollectionPicker by remember { mutableStateOf(false) }

    // Refresh model state once when the screen first composes
    LaunchedEffect(Unit) { viewModel.refreshModelState() }

    // Auto-scroll to latest message
    LaunchedEffect(uiState.allMessages.size) {
        if (uiState.allMessages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.allMessages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionsDrawerContent(
                sessions        = uiState.sessions,
                activeSessionId = uiState.activeSessionId,
                onNewSession    = {
                    viewModel.createNewSession()
                    scope.launch { drawerState.close() }
                },
                onSelectSession = { id ->
                    viewModel.switchToSession(id)
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = { id -> viewModel.deleteSession(id) },
                onRenameSession = { id, title -> viewModel.renameSession(id, title) }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Surface0)) {

            // ── Top bar ───────────────────────────────────────────────────────
            ChatTopBar(
                sessionTitle  = uiState.activeSessionTitle,
                isModelLoaded = uiState.isModelLoaded,
                modelName     = uiState.loadedModelName,
                onMenuClick   = { scope.launch { drawerState.open() } },
                onClearChat   = { viewModel.clearCurrentChat() }
            )

            // ── Auto-load model banner ────────────────────────────────────────
            AnimatedVisibility(visible = uiState.isAutoLoadingModel) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TealPrimary.copy(alpha = 0.10f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = TealPrimary,
                        strokeWidth = 2.dp
                    )
                    Text(uiState.autoLoadStatus, color = TealPrimary, fontSize = 13.sp)
                }
            }

            // ── Error banner ──────────────────────────────────────────────────
            AnimatedVisibility(visible = uiState.errorMessage != null) {
                uiState.errorMessage?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ErrorRed.copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, tint = ErrorRed, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // ── Messages or empty state ───────────────────────────────────────
            if (uiState.allMessages.isEmpty()) {
                ChatEmptyState(modifier = Modifier.weight(1f).fillMaxWidth()) { suggestion ->
                    viewModel.setInputText(suggestion)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        uiState.allMessages,
                        key = { it.id }
                    ) { message ->
                        val canInteract = !message.isStreaming && !uiState.isGenerating
                        MessageBubble(
                            message       = message,
                            onEditQuery   = if (canInteract && message.role == "user") {
                                { newText -> viewModel.editAndResendMessage(message.id, newText) }
                            } else null,
                            onRestartFrom = if (canInteract && message.role == "user") {
                                { viewModel.restartFromMessage(message.id) }
                            } else null
                        )
                    }
                }
            }

            // ── Collection selector row (above input bar) ─────────────────────
            CollectionSelectorRow(
                collectionName = uiState.chatCollectionName,
                isNoCollection = uiState.chatCollectionId == null,
                isGenerating   = uiState.isGenerating,
                onClick        = { showCollectionPicker = true }
            )

            // ── Input bar ─────────────────────────────────────────────────────
            ChatInputBar(
                text            = uiState.inputText,
                isGenerating    = uiState.isGenerating,
                isAutoLoading   = uiState.isAutoLoadingModel,
                enableThinking  = uiState.enableThinking,
                onTextChange    = { viewModel.setInputText(it) },
                onSend          = { scope.launch { viewModel.sendMessage(uiState.inputText) } },
                onStop          = { viewModel.stopGeneration() },
                onToggleThinking = { viewModel.toggleThinking() }
            )
        }
    }

    // ── Collection picker bottom sheet ────────────────────────────────────────
    if (showCollectionPicker) {
        CollectionPickerSheet(
            collections = uiState.collections,
            currentId   = uiState.chatCollectionId,
            onSelect    = { id ->
                viewModel.selectChatCollection(id)
                showCollectionPicker = false
            },
            onDismiss   = { showCollectionPicker = false }
        )
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    sessionTitle: String,
    isModelLoaded: Boolean,
    modelName: String,
    onMenuClick: () -> Unit,
    onClearChat: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Surface(color = Surface1, shadowElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Outlined.Menu, "Sessions", tint = TextSecondary)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedContent(
                    targetState = sessionTitle.ifBlank { "New Chat" },
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "top_bar_title"
                ) { title ->
                    Text(
                        title,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        letterSpacing = (-0.2).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isModelLoaded) SuccessGreen else TextHint)
                    )
                    AnimatedContent(
                        targetState = if (isModelLoaded) modelName.ifBlank { "Model ready" } else "No model loaded",
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                        label = "top_bar_subtitle"
                    ) { subtitle ->
                        Text(
                            subtitle,
                            color = if (isModelLoaded) TextSecondary else WarningAmber,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            IconButton(onClick = { showClearConfirm = true }) {
                Icon(Icons.Default.Delete, "Clear chat", tint = TextHint)
            }
        }
    }
    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Surface2,
            title = { Text("Clear conversation?", color = TextPrimary) },
            text  = { Text("All messages in this session will be permanently deleted.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { onClearChat(); showClearConfirm = false }) {
                    Text("Clear", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// ── Sessions drawer ───────────────────────────────────────────────────────────

@Composable
private fun SessionsDrawerContent(
    sessions: List<ChatSessionEntity>,
    activeSessionId: String,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = Surface1,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.width(300.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Surface0, Surface1)))
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)
        ) {
            Column {
                Text(
                    "Anvit",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
                Text("AI Document Assistant", color = TextSecondary, fontSize = 12.sp)
            }
        }

        HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onNewSession() },
            shape  = RoundedCornerShape(12.dp),
            color  = TealPrimary.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, TealPrimary.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = TealPrimary, modifier = Modifier.size(18.dp))
                Text("New Chat", color = TealPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Chat, null, tint = TextHint, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(4.dp))
                Text("No conversations yet", color = TextSecondary, fontSize = 13.sp)
                Text("Tap 'New Chat' to get started", color = TextHint, fontSize = 11.sp)
            }
        } else {
            val grouped = remember(sessions) { groupSessionsByTime(sessions) }
            LazyColumn {
                grouped.forEach { (label, groupSessions) ->
                    item(key = "header_$label") {
                        Text(
                            label.uppercase(),
                            color = TextHint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp, end = 16.dp)
                        )
                    }
                    items(groupSessions, key = { it.id }) { session ->
                        SessionItem(
                            session  = session,
                            isActive = session.id == activeSessionId,
                            onClick  = { onSelectSession(session.id) },
                            onDelete = { onDeleteSession(session.id) },
                            onRename = { newTitle -> onRenameSession(session.id, newTitle) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSessionEntity,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    var showRenameDialog  by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText        by remember(session.title) { mutableStateOf(session.title) }

    val bgColor by animateColorAsState(
        targetValue = if (isActive) TealPrimary.copy(alpha = 0.09f) else Color.Transparent,
        label = "session_bg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isActive) TealPrimary else Color.Transparent)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (isActive) TealPrimary.copy(alpha = 0.14f) else Surface3),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Chat, null,
                tint = if (isActive) TealPrimary else TextHint,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(
                session.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(formatRelativeTime(session.updatedAt), color = TextHint, fontSize = 10.sp)
        }
        Box {
            var menuExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, null, tint = TextHint, modifier = Modifier.size(15.dp))
            }
            DropdownMenu(
                expanded         = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor   = Surface3
            ) {
                DropdownMenuItem(
                    text = { Text("Rename", color = TextPrimary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = TextSecondary, modifier = Modifier.size(16.dp)) },
                    onClick = { menuExpanded = false; showRenameDialog = true }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = ErrorRed, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = ErrorRed, modifier = Modifier.size(16.dp)) },
                    onClick = { menuExpanded = false; showDeleteConfirm = true }
                )
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor   = Surface2,
            title = { Text("Rename Chat", color = TextPrimary) },
            text  = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = TealPrimary,
                        unfocusedBorderColor = BorderDefault,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(renameText); showRenameDialog = false }) {
                    Text("Save", color = TealPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor   = Surface2,
            title = { Text("Delete chat?", color = TextPrimary) },
            text  = { Text("\"${session.title}\" and all its messages will be deleted.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

private data class SuggestionItem(val label: String, val prompt: String)

@Composable
private fun ChatEmptyState(modifier: Modifier = Modifier, onSuggestionClick: (String) -> Unit) {
    val suggestions = listOf(
        SuggestionItem("Summarize documents", "Give me a concise summary of all my uploaded documents."),
        SuggestionItem("Key findings", "What are the most important findings or conclusions in my documents?"),
        SuggestionItem("Compare sections", "Compare and contrast the main sections across my documents."),
        SuggestionItem("List action items", "Extract all action items or next steps mentioned in my documents.")
    )

    Column(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TealPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                null,
                tint = TealPrimary.copy(alpha = 0.8f),
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Ask Anvit anything", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Upload PDFs in the Documents tab, then ask questions here.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "Try asking",
            color = TextHint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(Modifier.height(8.dp))
        suggestions.forEach { suggestion ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable { onSuggestionClick(suggestion.prompt) },
                shape   = RoundedCornerShape(12.dp),
                color   = Surface2,
                border  = BorderStroke(0.5.dp, BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = TealPrimary, modifier = Modifier.size(16.dp))
                    Column {
                        Text(suggestion.label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            suggestion.prompt, color = TextSecondary, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ── Input bar ─────────────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    isAutoLoading: Boolean,
    enableThinking: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleThinking: () -> Unit
) {
    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
    Surface(color = Surface1) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {

            // ── Text field + send/stop button ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(24.dp),
                    color    = Surface3,
                    border   = BorderStroke(0.5.dp, if (isGenerating) BorderSubtle else BorderDefault)
                ) {
                    Column {
                        TextField(
                            value         = text,
                            onValueChange = onTextChange,
                            placeholder   = {
                                Text(
                                    when {
                                        isAutoLoading -> "Loading model…"
                                        isGenerating  -> "Generating…"
                                        else          -> "Ask about your documents…"
                                    },
                                    color    = TextHint,
                                    fontSize = 14.sp
                                )
                            },
                            enabled = !isGenerating && !isAutoLoading,
                            colors  = TextFieldDefaults.colors(
                                focusedContainerColor   = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor  = Color.Transparent,
                                focusedTextColor        = TextPrimary,
                                unfocusedTextColor      = TextPrimary,
                                disabledTextColor       = TextSecondary,
                                focusedIndicatorColor   = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor  = Color.Transparent,
                                cursorColor             = TealPrimary
                            ),
                            modifier  = Modifier.fillMaxWidth(),
                            maxLines  = 5,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                        )

                        // ── Bottom toolbar row inside the pill ─────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Think toggle
                            val thinkingTint by animateColorAsState(
                                targetValue = if (enableThinking) TealPrimary else TextSecondary,
                                animationSpec = tween(200),
                                label = "thinking_tint"
                            )
                            val thinkingBg by animateColorAsState(
                                targetValue = if (enableThinking) TealPrimary.copy(alpha = 0.12f) else Color.Transparent,
                                animationSpec = tween(200),
                                label = "thinking_bg"
                            )
                            Surface(
                                shape    = RoundedCornerShape(20.dp),
                                color    = thinkingBg,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(enabled = !isGenerating) { onToggleThinking() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector    = Icons.Default.Lightbulb,
                                        contentDescription = if (enableThinking) "Disable thinking" else "Enable thinking",
                                        tint           = thinkingTint,
                                        modifier       = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text     = "Think",
                                        color    = thinkingTint,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // Send → Stop toggle button
                            val canSend = text.isNotBlank() && !isAutoLoading
                            FilledIconButton(
                                onClick  = { if (isGenerating) onStop() else onSend() },
                                enabled  = isGenerating || canSend,
                                modifier = Modifier.size(36.dp),
                                shape    = CircleShape,
                                colors   = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (isGenerating) ErrorRed.copy(alpha = 0.15f) else TealPrimary,
                                    contentColor   = if (isGenerating) ErrorRed else Surface0,
                                    disabledContainerColor = TealPrimary.copy(alpha = 0.15f),
                                    disabledContentColor   = TealPrimary.copy(alpha = 0.4f)
                                )
                            ) {
                                AnimatedContent(
                                    targetState  = isGenerating,
                                    transitionSpec = {
                                        scaleIn(tween(160)) + fadeIn(tween(160)) togetherWith
                                        scaleOut(tween(160)) + fadeOut(tween(160))
                                    },
                                    label = "send_stop_icon"
                                ) { generating ->
                                    if (generating) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = "Stop generation",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Send,
                                            contentDescription = "Send",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Collection selector row (above input bar) ─────────────────────────────────

@Composable
private fun CollectionSelectorRow(
    collectionName: String,
    isNoCollection: Boolean,
    isGenerating: Boolean,
    onClick: () -> Unit
) {
    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Search in:", color = TextPrimary, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = !isGenerating) { onClick() },
            shape  = RoundedCornerShape(6.dp),
            color  = if (isNoCollection) Surface3 else TealPrimary.copy(alpha = 0.10f),
            border = BorderStroke(
                0.5.dp,
                if (isNoCollection) BorderDefault else TealPrimary.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    if (isNoCollection) Icons.Default.FolderOff else Icons.Default.Folder,
                    null,
                    tint = if (isNoCollection) TextSecondary else TealPrimary,
                    modifier = Modifier.size(12.dp)
                )
                AnimatedContent(
                    targetState = collectionName,
                    transitionSpec = {
                        (slideInVertically(tween(160)) { it / 2 } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(160)) { -it / 2 } + fadeOut(tween(160)))
                    },
                    label = "selector_collection_name"
                ) { name ->
                    Text(
                        name,
                        color      = if (isNoCollection) TextSecondary else TealPrimary,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    null,
                    tint     = if (isNoCollection) TextSecondary else TealPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── Collection picker bottom sheet ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionPickerSheet(
    collections: List<CollectionEntity>,
    currentId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Surface2,
        dragHandle       = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BorderDefault)
            )
        }
    ) {
        Text(
            "Search in…",
            color      = TextPrimary,
            fontSize   = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

        CollectionPickerRow(
            icon       = Icons.Default.FolderOff,
            name       = "No collection",
            subtitle   = "Answer directly, no documents",
            isSelected = currentId == null,
            iconTint   = TextHint,
            onClick    = { onSelect(null) }
        )

        collections.forEach { coll ->
            CollectionPickerRow(
                icon       = Icons.Default.Folder,
                name       = coll.name,
                subtitle   = if (coll.description.isNotBlank()) coll.description else null,
                isSelected = currentId == coll.id,
                iconTint   = TealPrimary,
                onClick    = { onSelect(coll.id) }
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CollectionPickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    subtitle: String?,
    isSelected: Boolean,
    iconTint: Color,
    onClick: () -> Unit
) {
    val rowBg = if (isSelected) TealPrimary.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RadioButton(
            selected = isSelected,
            onClick  = { onClick() },
            colors   = RadioButtonDefaults.colors(
                selectedColor   = TealPrimary,
                unselectedColor = TextHint
            )
        )
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name, color = TextPrimary, fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = TextHint, fontSize = 11.sp)
            }
        }
        if (isSelected) {
            Icon(Icons.Default.Check, null, tint = TealPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onEditQuery: ((String) -> Unit)? = null,
    onRestartFrom: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showEditDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow))
    ) {
        if (!isUser && message.agentSteps.isNotEmpty()) {
            AgentStepsPanel(message.agentSteps, modifier = Modifier.padding(bottom = 4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Column(
                modifier = if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.weight(1f)
            ) {
                // Thinking section
                if (!isUser && message.thinkingContent.isNotEmpty()) {
                    ThinkingSection(
                        content     = message.thinkingContent,
                        isStreaming = message.isStreaming && message.content.isEmpty()
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // Main bubble
                if (message.content.isNotEmpty() ||
                    (message.isStreaming && message.thinkingContent.isEmpty())
                ) {
                    val shape = RoundedCornerShape(
                        topStart = 20.dp, topEnd = 6.dp,
                        bottomStart = 20.dp, bottomEnd = 20.dp
                    )

                    if (isUser) {
                        Column(horizontalAlignment = Alignment.End) {
                            if (message.content.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .clip(shape)
                                        .background(UserBubble)
                                        .border(0.5.dp, UserBubbleBorder, shape)
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        message.content,
                                        color      = TextPrimary,
                                        fontSize   = 14.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                            }

                            // User message action row
                            if (!message.isStreaming) {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    if (onRestartFrom != null) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(onClick = onRestartFrom)
                                                .padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(Icons.Default.Replay, null, tint = TextHint, modifier = Modifier.size(16.dp))
                                            Text("Restart from here", color = TextHint, fontSize = 12.sp)
                                        }
                                    }
                                    if (onEditQuery != null) {
                                        IconButton(onClick = { showEditDialog = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Edit, "Edit query", tint = TextHint, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    IconButton(
                                        onClick  = { clipboardManager.setText(AnnotatedString(message.content)) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, "Copy query", tint = TextHint, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                            if (message.content.isNotEmpty()) {
                                MarkdownText(text = message.content, color = TextPrimary)
                            }
                            if (message.isStreaming && message.content.isEmpty() && message.thinkingContent.isEmpty()) {
                                StreamingDots()
                            }
                        }
                    }
                } else if (message.isStreaming && message.thinkingContent.isNotEmpty() && message.content.isEmpty()) {
                    StreamingDots()
                }

                // "Stopped" badge
                if (!isUser && message.wasStopped) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            null,
                            tint     = WarningAmber.copy(alpha = 0.7f),
                            modifier = Modifier.size(10.dp)
                        )
                        Text("Generation stopped", color = WarningAmber.copy(alpha = 0.7f), fontSize = 10.sp)
                    }
                }

                // "Direct chat" badge
                if (!isUser && message.usedDirectMode) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.Chat, null, tint = TextHint, modifier = Modifier.size(10.dp))
                        Text("Direct chat", color = TextHint, fontSize = 10.sp)
                    }
                }

                // Assistant copy action
                if (!isUser && !message.isStreaming && message.content.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick  = { clipboardManager.setText(AnnotatedString(message.content)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "Copy response", tint = TextHint, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (!isUser && message.usedSources.isNotEmpty()) {
                    SourcesPanel(message.usedSources)
                }

                // Timestamp
                val timeStr = remember(message.id) {
                    val ldt = Instant.fromEpochMilliseconds(currentTimeMillis())
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                    "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}"
                }
                Text(
                    timeStr,
                    color    = TextHint,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .then(if (isUser) Modifier.align(Alignment.End) else Modifier)
                )
            }

            if (isUser) Spacer(Modifier.width(4.dp))
        }
    }

    if (showEditDialog) {
        EditQueryDialog(
            initialText = message.content,
            onConfirm   = { newText ->
                onEditQuery?.invoke(newText)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false }
        )
    }
}

// ── Edit query dialog ─────────────────────────────────────────────────────────

@Composable
private fun EditQueryDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = { Text("Edit message", color = TextPrimary) },
        text  = {
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                modifier      = Modifier.fillMaxWidth(),
                minLines      = 3,
                maxLines      = 8,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = TealPrimary,
                    unfocusedBorderColor = BorderDefault,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = TealPrimary
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
            )
        },
        confirmButton = {
            Button(
                onClick  = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled  = text.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = TealPrimary,
                    contentColor           = Surface0,
                    disabledContainerColor = TealPrimary.copy(alpha = 0.3f),
                    disabledContentColor   = Surface0.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

// ── Sources panel ─────────────────────────────────────────────────────────────

@Composable
private fun SourcesPanel(sources: List<SourceChunk>) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 16.dp)) {
        var selectedSource by remember { mutableStateOf<SourceChunk?>(null) }
        Text(
            "Sources", color = TextSecondary, fontSize = 12.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp)
        )
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources) { source ->
                Surface(
                    shape    = RoundedCornerShape(8.dp),
                    color    = Surface2,
                    border   = BorderStroke(0.5.dp, BorderDefault),
                    modifier = Modifier.width(160.dp).clickable { selectedSource = source }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, null, tint = TealPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                source.title, color = TextPrimary, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            source.snippet, color = TextSecondary, fontSize = 11.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        if (selectedSource != null) {
            AlertDialog(
                onDismissRequest = { selectedSource = null },
                containerColor   = Surface2,
                title = { Text(selectedSource!!.title, color = TextPrimary, fontSize = 16.sp) },
                text  = {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        item { Text(selectedSource!!.snippet, color = TextPrimary, fontSize = 14.sp, lineHeight = 22.sp) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedSource = null }) { Text("Close", color = TealPrimary) }
                }
            )
        }
    }
}

// ── Animated streaming dots ───────────────────────────────────────────────────

@Composable
private fun StreamingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        0.3f at (index * 200)
                        1.0f at (index * 200 + 300)
                        0.3f at (index * 200 + 600)
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(TealPrimary.copy(alpha = alpha))
            )
        }
    }
}

// ── Collapsible thinking section ──────────────────────────────────────────────

@Composable
private fun ThinkingSection(content: String, isStreaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(isStreaming) {
        if (!isStreaming) expanded = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart    = 4.dp,  topEnd    = 12.dp,
                        bottomStart = if (expanded) 0.dp else 12.dp,
                        bottomEnd   = if (expanded) 0.dp else 12.dp
                    )
                )
                .background(Surface2)
                .border(
                    0.5.dp,
                    BorderSubtle,
                    RoundedCornerShape(
                        topStart    = 4.dp,  topEnd    = 12.dp,
                        bottomStart = if (expanded) 0.dp else 12.dp,
                        bottomEnd   = if (expanded) 0.dp else 12.dp
                    )
                )
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(12.dp),
                        color       = AgentStepBlue,
                        strokeWidth = 1.5.dp
                    )
                } else {
                    Icon(Icons.Default.Lightbulb, null, tint = AgentStepBlue, modifier = Modifier.size(15.dp))
                }
                Text(
                    if (isStreaming) "Thinking…" else "Thought",
                    color = AgentStepBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint     = AgentStepBlue.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(Surface2)
                    .border(
                        0.5.dp,
                        BorderSubtle,
                        RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                ProvideTextStyle(
                    androidx.compose.ui.text.TextStyle(
                        fontSize   = 13.sp,
                        lineHeight = 19.sp,
                        fontStyle  = FontStyle.Italic
                    )
                ) {
                    MarkdownText(text = content, color = TextPrimary)
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun groupSessionsByTime(
    sessions: List<ChatSessionEntity>
): List<Pair<String, List<ChatSessionEntity>>> {
    val tz          = TimeZone.currentSystemDefault()
    val todayDate   = Instant.fromEpochMilliseconds(currentTimeMillis()).toLocalDateTime(tz).date
    val todayStart  = todayDate.atStartOfDayIn(tz).toEpochMilliseconds()
    val yesterdayStart = todayStart - 86_400_000L

    val today     = sessions.filter { it.updatedAt >= todayStart }
    val yesterday = sessions.filter { it.updatedAt in yesterdayStart until todayStart }
    val earlier   = sessions.filter { it.updatedAt < yesterdayStart }

    return buildList {
        if (today.isNotEmpty())     add("Today"     to today)
        if (yesterday.isNotEmpty()) add("Yesterday" to yesterday)
        if (earlier.isNotEmpty())   add("Earlier"   to earlier)
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = currentTimeMillis() - timestamp
    return when {
        diff < 60_000         -> "Just now"
        diff < 3_600_000      -> "${diff / 60_000}m ago"
        diff < 86_400_000     -> "${diff / 3_600_000}h ago"
        diff < 2 * 86_400_000 -> "Yesterday"
        else -> {
            val ldt = Instant.fromEpochMilliseconds(timestamp)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val month = ldt.month.name.take(3).lowercase()
                .replaceFirstChar { it.uppercaseChar() }
            "$month ${ldt.dayOfMonth}"
        }
    }
}
