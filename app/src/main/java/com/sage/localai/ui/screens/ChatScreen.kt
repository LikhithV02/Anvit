package com.sage.localai.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.sage.localai.data.db.entities.ChatSessionEntity
import com.sage.localai.data.db.entities.CollectionEntity
import com.sage.localai.ui.components.AgentStepsPanel
import com.sage.localai.ui.components.MarkdownText
import com.sage.localai.ui.theme.*
import com.sage.localai.ui.viewmodels.ChatMessage
import com.sage.localai.ui.viewmodels.ChatViewModel
import com.sage.localai.ui.viewmodels.SourceChunk
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Root screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val uiState   by viewModel.uiState.collectAsState()
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Collection picker sheet state
    var showCollectionPicker by remember { mutableStateOf(false) }

    // Image picker launcher (API 33+ uses Photo Picker, older uses GetContent)
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.attachImage(context, it) } }

    // Refresh model state on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshModelState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-scroll to latest message (observe allMessages which includes streaming)
    LaunchedEffect(uiState.allMessages.size) {
        if (uiState.allMessages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.allMessages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionsDrawerContent(
                sessions       = uiState.sessions,
                activeSessionId = uiState.activeSessionId,
                onNewSession   = {
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
                    Text(
                        uiState.autoLoadStatus,
                        color = TealPrimary,
                        fontSize = 13.sp
                    )
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
                    items(uiState.allMessages, key = { it.id }) { message ->
                        val canRegenerate = !message.isStreaming && message.role == "assistant" && !uiState.isGenerating
                        MessageBubble(
                            message = message,
                            onRegenerate = if (canRegenerate) {
                                { viewModel.regenerateMessage(message.id) }
                            } else null
                        )
                    }
                }
            }

            // ── Collection selector row (above input bar) ─────────────────────
            CollectionSelectorRow(
                collectionName  = uiState.chatCollectionName,
                isNoCollection  = uiState.chatCollectionId == null,
                isGenerating    = uiState.isGenerating,
                onClick         = { showCollectionPicker = true }
            )

            // ── Input bar ─────────────────────────────────────────────────────
            ChatInputBar(
                text              = uiState.inputText,
                isGenerating      = uiState.isGenerating,
                isAutoLoading     = uiState.isAutoLoadingModel,
                pendingImagePath  = uiState.pendingImagePath,
                onTextChange      = { viewModel.setInputText(it) },
                onSend            = { scope.launch { viewModel.sendMessage(uiState.inputText) } },
                onStop            = { viewModel.stopGeneration() },
                onAttachImage     = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onClearImage      = { viewModel.clearAttachedImage() }
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

            // Centre block — brand name + session title
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Chat Session",
                    color = TealPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    letterSpacing = (-0.3).sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Live status dot
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isModelLoaded) SuccessGreen else TextHint)
                    )
                    AnimatedContent(
                        targetState = if (isModelLoaded) sessionTitle else "No model loaded",
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                        },
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

            IconButton(onClick = onClearChat) {
                Icon(Icons.Default.Delete, "Clear chat", tint = TextHint)
            }
        }
    }
    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
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
        drawerContainerColor = Surface2,
        modifier = Modifier.width(300.dp)
    ) {
        // Gradient header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Surface0, Surface2)))
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Column {
                Text(
                    "Sage",
                    color = TealPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    "AI Document Assistant",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

        // New chat button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNewSession() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TealPrimary.copy(alpha = 0.15f))
                    .border(1.dp, BorderFocus, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, null, tint = TealPrimary, modifier = Modifier.size(18.dp))
            }
            Text("New Chat", color = TealPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No conversations yet", color = TextHint, fontSize = 13.sp)
            }
        } else {
            Text(
                "RECENT",
                color = TextHint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            LazyColumn {
                items(sessions, key = { it.id }) { session ->
                    SessionItem(
                        session   = session,
                        isActive  = session.id == activeSessionId,
                        onClick   = { onSelectSession(session.id) },
                        onDelete  = { onDeleteSession(session.id) },
                        onRename  = { newTitle -> onRenameSession(session.id, newTitle) }
                    )
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
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText by remember(session.title) { mutableStateOf(session.title) }

    val bgColor by animateColorAsState(
        targetValue = if (isActive) TealPrimary.copy(alpha = 0.12f) else Color.Transparent,
        label = "session_bg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Active indicator bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isActive) TealPrimary else Color.Transparent)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                session.title,
                color = if (isActive) TextPrimary else TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatRelativeTime(session.updatedAt),
                color = TextHint,
                fontSize = 11.sp
            )
        }
        // Actions
        Box {
            var menuExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, null, tint = TextHint, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = Surface3
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
            containerColor = Surface2,
            title = { Text("Rename Chat", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = BorderDefault,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
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
            containerColor = Surface2,
            title = { Text("Delete chat?", color = TextPrimary) },
            text = { Text("\"${session.title}\" and all its messages will be deleted.", color = TextSecondary) },
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

@Composable
private fun ChatEmptyState(modifier: Modifier = Modifier, onSuggestionClick: (String) -> Unit) {
    val suggestions = listOf("Summarize my documents", "What are the key findings?", "Compare sections")

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            null,
            tint = TealPrimary.copy(alpha = 0.4f),
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Ask Sage anything", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Upload PDFs in the Documents tab,\nthen ask questions here.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(28.dp))
        suggestions.forEach { suggestion ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSuggestionClick(suggestion) },
                shape = RoundedCornerShape(12.dp),
                color = Surface2,
                border = BorderStroke(0.5.dp, BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = TealPrimary, modifier = Modifier.size(16.dp))
                    Text(suggestion, color = TextSecondary, fontSize = 14.sp)
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
    pendingImagePath: String?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: () -> Unit,
    onClearImage: () -> Unit
) {
    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
    Surface(color = Surface1) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {

            // ── Image thumbnail strip (when image is attached) ─────────────
            /*
            if (pendingImagePath != null) {
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.wrapContentSize()) {
                    AsyncImage(
                        model = File(pendingImagePath),
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 72.dp, height = 72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, BorderDefault, RoundedCornerShape(8.dp))
                    )
                    // Remove button overlaid at top-right
                    IconButton(
                        onClick = onClearImage,
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(Surface3)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove image",
                            tint = TextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            */

            // ── Text field + send/stop button ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = Surface3,
                    border = BorderStroke(0.5.dp, if (isGenerating) BorderSubtle else BorderDefault)
                ) {
                    Column {
                        TextField(
                            value = text,
                            onValueChange = onTextChange,
                            placeholder = {
                                Text(
                                    when {
                                        isAutoLoading -> "Loading model…"
                                        isGenerating  -> "Generating…"
                                        else          -> "Ask about your documents…"
                                    },
                                    color = TextHint,
                                    fontSize = 14.sp
                                )
                            },
                            enabled = !isGenerating && !isAutoLoading,
                            colors = TextFieldDefaults.colors(
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
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                        )
                        // "Attach image" button inside the text field (bottom area)
                        /*
                        if (!isGenerating && !isAutoLoading && pendingImagePath == null) {
                            TextButton(
                                onClick = onAttachImage,
                                modifier = Modifier
                                    .padding(start = 4.dp, bottom = 2.dp)
                                    .height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    null,
                                    tint = TextHint,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Attach image", color = TextHint, fontSize = 11.sp)
                            }
                        }
                        */
                    }
                }

                // Send → Stop toggle button
                FilledIconButton(
                    onClick = { if (isGenerating) onStop() else onSend() },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isGenerating) ErrorRed.copy(alpha = 0.15f) else TealPrimary,
                        contentColor   = if (isGenerating) ErrorRed else Surface0
                    )
                ) {
                    AnimatedContent(
                        targetState = isGenerating,
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
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
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
        Text("Search in:", color = TextHint, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = !isGenerating) { onClick() },
            shape = RoundedCornerShape(6.dp),
            color = if (isNoCollection) Surface3 else TealPrimary.copy(alpha = 0.10f),
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
                        color = if (isNoCollection) TextSecondary else TealPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    null,
                    tint = if (isNoCollection) TextSecondary else TealPrimary,
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
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

        // "No collection" row — always first
        CollectionPickerRow(
            icon       = Icons.Default.FolderOff,
            name       = "No collection",
            subtitle   = "Answer directly, no documents",
            isSelected = currentId == null,
            iconTint   = TextHint,
            onClick    = { onSelect(null) }
        )

        // All collections from DB
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
            Text(name, color = TextPrimary, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
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
private fun MessageBubble(message: ChatMessage, onRegenerate: (() -> Unit)? = null) {
    val isUser = message.role == "user"

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
            if (!isUser) {
                // Sage avatar
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(TealDark, TealPrimary))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", color = Surface0, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }

            Column(modifier = Modifier.widthIn(max = 300.dp)) {
                // Thinking section
                if (!isUser && message.thinkingContent.isNotEmpty()) {
                    ThinkingSection(
                        content = message.thinkingContent,
                        isStreaming = message.isStreaming && message.content.isEmpty()
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // Main bubble
                if (message.content.isNotEmpty() || (message.isStreaming && message.thinkingContent.isEmpty())) {
                    val shape = if (isUser) {
                        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                    } else {
                        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                    }

                    if (isUser) {
                        // User bubble: styled with border
                        Column {
                            // Show attached image above text if present
                            if (message.imagePath != null) {
                                AsyncImage(
                                    model            = File(message.imagePath),
                                    contentDescription = "Attached image",
                                    contentScale     = ContentScale.Crop,
                                    modifier         = Modifier
                                        .widthIn(max = 220.dp)
                                        .heightIn(max = 180.dp)
                                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                )
                                if (message.content.isNotEmpty()) Spacer(Modifier.height(4.dp))
                            }
                            if (message.content.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .clip(shape)
                                        .background(UserBubble)
                                        .border(0.5.dp, UserBubbleBorder, shape)
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(message.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                                }
                            }
                        }
                    } else {
                        // Assistant bubble with left accent bar
                        Row {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(TealPrimary)
                                    .alignByBaseline()
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(shape)
                                    .background(AssistantBubble)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                if (message.content.isNotEmpty()) {
                                    MarkdownText(
                                        text = message.content,
                                        color = TextPrimary
                                    )
                                }
                                if (message.isStreaming && message.content.isEmpty() && message.thinkingContent.isEmpty()) {
                                    StreamingDots()
                                }
                            }
                        }
                    }
                } else if (message.isStreaming && message.thinkingContent.isNotEmpty() && message.content.isEmpty()) {
                    // Still thinking — minimal indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(TealPrimary))
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                                .background(AssistantBubble.copy(alpha = 0.6f))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            StreamingDots()
                        }
                    }
                }

                // "Stopped" badge — shown on partially-generated messages
                if (!isUser && message.wasStopped) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp, start = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            null,
                            tint = WarningAmber.copy(alpha = 0.7f),
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            "Generation stopped",
                            color = WarningAmber.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                }

                // "Direct chat" badge — shown when no collection was used (no RAG)
                if (!isUser && message.usedDirectMode) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp, start = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Default.Chat,
                            null,
                            tint = TextHint,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            "Direct chat",
                            color = TextHint,
                            fontSize = 10.sp
                        )
                    }
                }

                if (!isUser && onRegenerate != null) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp, start = 9.dp)
                            .clickable(onClick = onRegenerate),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            null,
                            tint = TealPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            "Regenerate",
                            color = TealPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (!isUser && message.usedSources.isNotEmpty()) {
                    SourcesPanel(message.usedSources)
                }

                // Timestamp
                val timeStr = remember(message.id) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                }
                Text(
                    timeStr,
                    color = TextHint,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .padding(top = 3.dp, start = if (isUser) 0.dp else 9.dp)
                        .then(if (isUser) Modifier.align(Alignment.End) else Modifier)
                )
            }

            if (isUser) Spacer(Modifier.width(4.dp))
        }
    }
}

// ── Sources panel ─────────────────────────────────────────────────────────────

@Composable
private fun SourcesPanel(sources: List<SourceChunk>) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 9.dp)) {
        var selectedSource by remember { mutableStateOf<SourceChunk?>(null) }
        Text("Sources", color = TextHint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources) { source ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface2,
                    border = BorderStroke(0.5.dp, BorderDefault),
                    modifier = Modifier.width(160.dp).clickable { selectedSource = source }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, null, tint = TealPrimary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(source.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(source.snippet, color = TextSecondary, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                    }
                }
            }
        }
        
        if (selectedSource != null) {
            AlertDialog(
                onDismissRequest = { selectedSource = null },
                containerColor = Surface2,
                title = { Text(selectedSource!!.title, color = TextPrimary, fontSize = 16.sp) },
                text = { 
                   LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) { 
                      item { Text(selectedSource!!.snippet, color = TextSecondary, fontSize = 14.sp) }
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = 4.dp, topEnd = 12.dp,
                        bottomStart = if (expanded) 0.dp else 12.dp,
                        bottomEnd   = if (expanded) 0.dp else 12.dp
                    )
                )
                .background(Surface2)
                .border(0.5.dp, BorderSubtle, RoundedCornerShape(
                    topStart = 4.dp, topEnd = 12.dp,
                    bottomStart = if (expanded) 0.dp else 12.dp,
                    bottomEnd   = if (expanded) 0.dp else 12.dp
                ))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp), color = AgentStepBlue, strokeWidth = 1.5.dp
                    )
                } else {
                    Icon(Icons.Default.Lightbulb, null, tint = AgentStepBlue, modifier = Modifier.size(13.dp))
                }
                Text(
                    if (isStreaming) "Thinking…" else "Thought",
                    color = AgentStepBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint = AgentStepBlue.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
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
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontStyle = FontStyle.Italic
                    )
                ) {
                    MarkdownText(
                        text = content,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000         -> "Just now"
        diff < 3_600_000      -> "${diff / 60_000}m ago"
        diff < 86_400_000     -> "${diff / 3_600_000}h ago"
        diff < 2 * 86_400_000 -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
