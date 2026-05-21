@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.anvit.localai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import anvit.composeapp.generated.resources.Res
import anvit.composeapp.generated.resources.logo
import com.anvit.localai.data.db.entities.ChatSessionEntity
import com.anvit.localai.data.db.entities.CollectionEntity
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.ui.components.AgentStepsPanel
import com.anvit.localai.ui.components.MarkdownText
import com.anvit.localai.ui.theme.*
import com.anvit.localai.ui.viewmodels.ChatMessage
import com.anvit.localai.ui.viewmodels.ChatViewModel
import com.anvit.localai.ui.viewmodels.SourceChunk
import com.anvit.localai.utils.currentTimeMillis
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.viewmodel.koinViewModel

// ── Root screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = koinViewModel()) {
    val c          = LocalAnvitColors.current
    val uiState    by viewModel.uiState.collectAsState()
    val listState   = rememberLazyListState()
    val scope       = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val uriHandler  = LocalUriHandler.current

    var showCollectionPicker by remember { mutableStateOf(false) }
    var showModelPicker      by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshModelState() }

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
                onNewSession    = { viewModel.createNewSession(); scope.launch { drawerState.close() } },
                onSelectSession = { id -> viewModel.switchToSession(id); scope.launch { drawerState.close() } },
                onDeleteSession = { id -> viewModel.deleteSession(id) },
                onRenameSession = { id, title -> viewModel.renameSession(id, title) },
                onPinSession    = { id, isPinned -> viewModel.pinSession(id, isPinned) },
                onClose         = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().background(c.bg)) {

            ChatTopBar(
                sessionTitle       = uiState.activeSessionTitle,
                isModelLoaded      = uiState.isModelLoaded,
                modelName          = uiState.loadedModelName,
                isPinned           = uiState.sessions.find { it.id == uiState.activeSessionId }?.isPinned ?: false,
                onMenuClick        = { scope.launch { drawerState.open() } },
                onNewSession       = { viewModel.createNewSession() },
                onClearChat        = { viewModel.clearCurrentChat() },
                onDeleteSession    = { viewModel.deleteSession(uiState.activeSessionId) },
                onRenameSession    = { title -> viewModel.renameSession(uiState.activeSessionId, title) },
                onPinSession       = { pinned -> viewModel.pinSession(uiState.activeSessionId, pinned) },
                onModelPickerClick = { showModelPicker = true },
            )

            // Auto-load model banner
            AnimatedVisibility(visible = uiState.isAutoLoadingModel) {
                val shimmerAnim = rememberInfiniteTransition(label = "shimmer")
                val shimmerX by shimmerAnim.animateFloat(
                    initialValue = -1f, targetValue = 2f,
                    animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)),
                    label = "shimmerX",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.surf2)
                        .border(1.dp, c.amber.copy(0.25f), RoundedCornerShape(14.dp)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(c.amber))
                            Text(uiState.autoLoadStatus, color = c.amber, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(c.surf3),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(Color.Transparent, c.amber.copy(0.8f), Color.Transparent),
                                            startX = shimmerX * 500f,
                                            endX   = shimmerX * 500f + 250f,
                                        )
                                    ),
                            )
                        }
                    }
                }
            }

            // Error banner
            AnimatedVisibility(visible = uiState.errorMessage != null) {
                uiState.errorMessage?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ErrorRed.copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
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

            if (uiState.allMessages.isEmpty()) {
                ChatEmptyState(modifier = Modifier.weight(1f).fillMaxWidth()) { suggestion ->
                    viewModel.setInputText(suggestion)
                }
            } else {
                LazyColumn(
                    state   = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.allMessages, key = { it.id }) { message ->
                        val canInteract = !message.isStreaming && !uiState.isGenerating
                        MessageBubble(
                            message               = message,
                            userEmail             = uiState.userEmail,
                            onEditQuery           = if (canInteract && message.role == "user") {
                                { newText -> viewModel.editAndResendMessage(message.id, newText) }
                            } else null,
                            onRestartFrom         = if (canInteract && message.role == "user") {
                                { viewModel.restartFromMessage(message.id) }
                            } else null,
                            onRestartGeneration   = if (canInteract && message.wasStopped) {
                                { viewModel.restartStoppedResponse(message.id) }
                            } else null,
                            onReportResponse      = if (canInteract && message.role == "assistant") {
                                { email, reason, content -> viewModel.submitReport(message.id, content, reason, email) }
                            } else null,
                        )
                    }
                }
            }

            CollectionSelectorRow(
                collectionName  = uiState.chatCollectionName,
                isNoCollection  = uiState.chatCollectionId == null,
                isGenerating    = uiState.isGenerating,
                onClick         = { showCollectionPicker = true },
            )

            ChatInputBar(
                text             = uiState.inputText,
                isGenerating     = uiState.isGenerating,
                isAutoLoading    = uiState.isAutoLoadingModel,
                enableThinking   = uiState.enableThinking,
                onTextChange     = { viewModel.setInputText(it) },
                onSend           = { scope.launch { viewModel.sendMessage(uiState.inputText) } },
                onStop           = { viewModel.stopGeneration() },
                onToggleThinking = { viewModel.toggleThinking() },
            )
        }
    }

    if (showCollectionPicker) {
        CollectionPickerSheet(
            collections = uiState.collections,
            currentId   = uiState.chatCollectionId,
            onSelect    = { id -> viewModel.selectChatCollection(id); showCollectionPicker = false },
            onDismiss   = { showCollectionPicker = false },
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models             = viewModel.availableModels,
            loadedModelName    = uiState.loadedModelName,
            isSwitching        = uiState.isSwitchingModel,
            isModelFilePresent = viewModel::isModelFilePresent,
            onLoad             = { model -> viewModel.loadModelFromPicker(model); showModelPicker = false },
            onDismiss          = { showModelPicker = false },
        )
    }


    if (uiState.showRatingDialog) {
        val c2 = LocalAnvitColors.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissRatingPrompt(permanent = false) },
            containerColor   = c2.surf2,
            title = { Text("Enjoying Anvit?", color = c2.txt0, fontWeight = FontWeight.Bold) },
            text  = { Text("Your rating helps us grow and improve. It only takes a second!", color = c2.txt1) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissRatingPrompt(permanent = true)
                    uriHandler.openUri("https://play.google.com/store/apps/details?id=com.likhith.anvit")
                }) {
                    Text("Rate Now ⭐", color = c2.accent)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.dismissRatingPrompt(permanent = true) }) {
                        Text("No Thanks", color = c2.txt1.copy(alpha = 0.6f))
                    }
                    TextButton(onClick = { viewModel.dismissRatingPrompt(permanent = false) }) {
                        Text("Maybe Later", color = c2.txt1)
                    }
                }
            },
        )
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun ChatTopBar(
    sessionTitle: String,
    isModelLoaded: Boolean,
    modelName: String,
    isPinned: Boolean,
    onMenuClick: () -> Unit,
    onNewSession: () -> Unit,
    onClearChat: () -> Unit,
    onDeleteSession: () -> Unit,
    onRenameSession: (String) -> Unit,
    onPinSession: (Boolean) -> Unit,
    onModelPickerClick: () -> Unit,
) {
    val c = LocalAnvitColors.current
    var showMenu         by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText       by remember(sessionTitle) { mutableStateOf(sessionTitle) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        // Left icon
        IconButton(
            onClick  = onMenuClick,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(Icons.Outlined.Menu, "Sessions", tint = c.txt1)
        }

        // Title + model — truly centered relative to the full bar width
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 96.dp), // 96dp = 2 × icon button width, keeps text from overlapping
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = sessionTitle.ifBlank { "New Chat" },
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "top_bar_title",
            ) { title ->
                Text(
                    title,
                    color = c.txt0,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.clickable(onClick = onModelPickerClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val dotColor = if (isModelLoaded) c.green else c.amber
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(dotColor))
                AnimatedContent(
                    targetState = if (isModelLoaded) modelName.ifBlank { "Ready" } else "No model loaded",
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "top_bar_subtitle",
                ) { subtitle ->
                    Text(
                        subtitle,
                        color = if (isModelLoaded) c.green else c.amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Switch model",
                    tint = if (isModelLoaded) c.green else c.amber,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Right icons
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = onNewSession) {
                Icon(Icons.Default.AddComment, "New Chat", tint = c.txt1)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More options", tint = c.txt2)
                }
                DropdownMenu(
                    expanded         = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor   = c.surf3,
                ) {
                    DropdownMenuItem(
                        text        = { Text("Rename", color = c.txt0, fontSize = 15.sp) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = c.txt1, modifier = Modifier.size(20.dp)) },
                        onClick     = { showMenu = false; renameText = sessionTitle; showRenameDialog = true },
                    )
                    DropdownMenuItem(
                        text        = { Text(if (isPinned) "Unpin" else "Pin", color = c.txt0, fontSize = 15.sp) },
                        leadingIcon = { CrossedPinIcon(tint = c.txt1, modifier = Modifier.size(20.dp), crossed = isPinned) },
                        onClick     = { showMenu = false; onPinSession(!isPinned) },
                    )
                    DropdownMenuItem(
                        text        = { Text("Clear Messages", color = c.txt0, fontSize = 15.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = c.txt1, modifier = Modifier.size(20.dp)) },
                        onClick     = { showMenu = false; showClearConfirm = true },
                    )
                    DropdownMenuItem(
                        text        = { Text("Delete Session", color = ErrorRed, fontSize = 15.sp) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = ErrorRed, modifier = Modifier.size(20.dp)) },
                        onClick     = { showMenu = false; showDeleteConfirm = true },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = c.border, thickness = 0.5.dp)

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor   = c.surf2,
            title = { Text("Rename Chat", color = c.txt0) },
            text  = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = c.accent,
                        unfocusedBorderColor = c.border2,
                        focusedTextColor     = c.txt0,
                        unfocusedTextColor   = c.txt0,
                        cursorColor          = c.accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onRenameSession(renameText); showRenameDialog = false }) {
                    Text("Save", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = c.txt2) }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor   = c.surf2,
            title = { Text("Clear conversation?", color = c.txt0) },
            text  = { Text("All messages in this session will be permanently deleted.", color = c.txt1) },
            confirmButton = {
                TextButton(onClick = { onClearChat(); showClearConfirm = false }) {
                    Text("Clear", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = c.txt2)
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor   = c.surf2,
            title = { Text("Delete session?", color = c.txt0) },
            text  = { Text("\"${sessionTitle}\" and all its messages will be permanently deleted.", color = c.txt1) },
            confirmButton = {
                TextButton(onClick = { onDeleteSession(); showDeleteConfirm = false }) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = c.txt2)
                }
            },
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
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onClose: () -> Unit = {},
) {
    val c = LocalAnvitColors.current
    ModalDrawerSheet(
        drawerContainerColor = c.surf1,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
    ) {
        // Header: logo + wordmark + close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surf0)
                .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter  = painterResource(Res.drawable.logo),
                contentDescription = "Anvit logo",
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Anvit", color = c.txt0, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                Text("AI Document Assistant", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Close", tint = c.txt2, modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = c.border, thickness = 0.5.dp)

        // New chat button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, c.border2, RoundedCornerShape(12.dp))
                .clickable { onNewSession() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Add, null, tint = c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Chat", color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        HorizontalDivider(color = c.border, thickness = 0.5.dp)

        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Chat, null, tint = c.txt3, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(4.dp))
                Text("No conversations yet", color = c.txt1, fontSize = 13.sp)
                Text("Tap 'New Chat' to get started", color = c.txt2, fontSize = 11.sp)
            }
        } else {
            val pinned   = remember(sessions) { sessions.filter { it.isPinned } }
            val unpinned = remember(sessions) { sessions.filter { !it.isPinned } }
            val grouped  = remember(unpinned) { groupSessionsByTime(unpinned) }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (pinned.isNotEmpty()) {
                    item(key = "header_Pinned") {
                        Text(
                            "PINNED",
                            color         = c.txt3,
                            fontSize      = 10.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            modifier      = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp, end = 16.dp),
                        )
                    }
                    items(pinned, key = { it.id }) { session ->
                        SessionItem(
                            session  = session,
                            isActive = session.id == activeSessionId,
                            onClick  = { onSelectSession(session.id) },
                            onDelete = { onDeleteSession(session.id) },
                            onRename = { newTitle -> onRenameSession(session.id, newTitle) },
                            onPin    = { isPinned -> onPinSession(session.id, isPinned) },
                        )
                    }
                }
                grouped.forEach { (label, groupSessions) ->
                    item(key = "header_$label") {
                        Text(
                            label.uppercase(),
                            color         = c.txt3,
                            fontSize      = 10.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            modifier      = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp, end = 16.dp),
                        )
                    }
                    items(groupSessions, key = { it.id }) { session ->
                        SessionItem(
                            session  = session,
                            isActive = session.id == activeSessionId,
                            onClick  = { onSelectSession(session.id) },
                            onDelete = { onDeleteSession(session.id) },
                            onRename = { newTitle -> onRenameSession(session.id, newTitle) },
                            onPin    = { isPinned -> onPinSession(session.id, isPinned) },
                        )
                    }
                }
            }
        }

        // Privacy footer
        HorizontalDivider(color = c.border, thickness = 0.5.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Shield, null, tint = c.txt3, modifier = Modifier.size(13.dp))
            Text("All data stays on your device", color = c.txt3, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSessionEntity,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onPin: (Boolean) -> Unit,
) {
    val c = LocalAnvitColors.current
    var showRenameDialog  by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText        by remember(session.title) { mutableStateOf(session.title) }

    val bgColor by animateColorAsState(
        targetValue = if (isActive) c.accentDim else Color.Transparent,
        label       = "session_bg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp).height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isActive) c.accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (isActive) c.accentDim else c.surf3),
            contentAlignment = Alignment.Center,
        ) {
            if (session.isPinned) {
                Icon(
                    Icons.Default.PushPin, null,
                    tint     = if (isActive) c.accent else c.txt2,
                    modifier = Modifier.size(15.dp),
                )
            } else {
                Icon(
                    Icons.Default.Chat, null,
                    tint     = if (isActive) c.accent else c.txt2,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(
                session.title,
                color      = c.txt0,
                fontSize   = 13.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            if (session.messageCount > 0) {
                Text(
                    "${session.messageCount} message${if (session.messageCount == 1) "" else "s"}",
                    color    = if (isActive) c.accent else c.txt2,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(formatRelativeTime(session.updatedAt), color = c.txt3, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
        Box {
            var menuExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, null, tint = c.txt2, modifier = Modifier.size(15.dp))
            }
            DropdownMenu(
                expanded         = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor   = c.surf3,
            ) {
                DropdownMenuItem(
                    text        = { Text("Rename", color = c.txt0, fontSize = 15.sp) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = c.txt1, modifier = Modifier.size(20.dp)) },
                    onClick     = { menuExpanded = false; showRenameDialog = true },
                )
                DropdownMenuItem(
                    text        = { Text(if (session.isPinned) "Unpin" else "Pin", color = c.txt0, fontSize = 15.sp) },
                    leadingIcon = { CrossedPinIcon(tint = c.txt1, modifier = Modifier.size(20.dp), crossed = session.isPinned) },
                    onClick     = { menuExpanded = false; onPin(!session.isPinned) },
                )
                DropdownMenuItem(
                    text        = { Text("Delete", color = ErrorRed, fontSize = 15.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ErrorRed, modifier = Modifier.size(20.dp)) },
                    onClick     = { menuExpanded = false; showDeleteConfirm = true },
                )
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor   = c.surf2,
            title = { Text("Rename Chat", color = c.txt0) },
            text  = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = c.accent,
                        unfocusedBorderColor = c.border2,
                        focusedTextColor     = c.txt0,
                        unfocusedTextColor   = c.txt0,
                        cursorColor          = c.accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(renameText); showRenameDialog = false }) {
                    Text("Save", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = c.txt2) }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor   = c.surf2,
            title = { Text("Delete chat?", color = c.txt0) },
            text  = { Text("\"${session.title}\" and all its messages will be deleted.", color = c.txt1) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = c.txt2) }
            },
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

private data class SuggestionItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val subtitle: String,
    val prompt: String,
)

@Composable
private fun ChatEmptyState(modifier: Modifier = Modifier, onSuggestionClick: (String) -> Unit) {
    val c = LocalAnvitColors.current
    val suggestions = listOf(
        SuggestionItem(Icons.Default.Layers,   "Summarize all documents",    "Get a structured overview of your uploads",      "Give me a concise summary of all my uploaded documents."),
        SuggestionItem(Icons.Default.Search,   "What are the key findings?", "Surface the most important conclusions",         "What are the most important findings or conclusions in my documents?"),
        SuggestionItem(Icons.Default.Language, "Compare across documents",   "Find similarities and differences",              "Compare and contrast the main sections across my documents."),
    )

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Logo
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = "Anvit",
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Ask Anvit anything",
            color         = c.txt0,
            fontSize      = 24.sp,
            fontWeight    = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            textAlign     = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your documents, answered privately.\nNothing leaves your device.",
            color      = c.txt2,
            fontSize   = 14.sp,
            textAlign  = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            "TRY ASKING",
            color         = c.txt2,
            fontSize      = 11.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier      = Modifier.align(Alignment.Start).padding(start = 4.dp, bottom = 12.dp),
        )
        suggestions.forEach { suggestion ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surf1)
                    .border(1.dp, c.border, RoundedCornerShape(16.dp))
                    .clickable { onSuggestionClick(suggestion.prompt) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.accentDim),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(suggestion.icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(suggestion.label,    color = c.txt0, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(suggestion.subtitle, color = c.txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = c.txt2, modifier = Modifier.size(18.dp))
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
    onToggleThinking: () -> Unit,
) {
    val c = LocalAnvitColors.current
    var isFocused by remember { mutableStateOf(false) }

    HorizontalDivider(color = c.border, thickness = 0.5.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surf1)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        val borderColor by animateColorAsState(
            targetValue = if (isFocused) c.border2 else c.border,
            animationSpec = tween(200),
            label = "input_border",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.surf2)
                .border(1.5.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Column {
                BasicTextField(
                    value         = text,
                    onValueChange = onTextChange,
                    enabled       = !isGenerating && !isAutoLoading,
                    maxLines      = 6,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text
                    ),
                    cursorBrush   = SolidColor(c.accent),
                    textStyle     = androidx.compose.ui.text.TextStyle(
                        color    = c.txt0,
                        fontSize = 14.sp,
                    ),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                when {
                                    isAutoLoading -> "Loading model…"
                                    isGenerating  -> "Generating…"
                                    else          -> "Ask anything about your documents…"
                                },
                                color    = c.txt3,
                                fontSize = 14.sp,
                            )
                        }
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth().onFocusChange { isFocused = it.isFocused },
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Think toggle
                        val thinkTint by animateColorAsState(
                            targetValue = if (enableThinking) c.amber else c.txt2,
                            label = "think_tint",
                        )
                        val thinkBg by animateColorAsState(
                            targetValue = if (enableThinking) c.amberDim else Color.Transparent,
                            label = "think_bg",
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(thinkBg)
                                .border(1.dp, if (enableThinking) c.amber.copy(0.3f) else Color.Transparent, RoundedCornerShape(100.dp))
                                .clickable(enabled = !isGenerating) { onToggleThinking() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.Lightbulb, null, tint = thinkTint, modifier = Modifier.size(18.dp))
                                Text("Think", color = thinkTint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                    }

                    // Send / Stop button
                    val canSend = text.isNotBlank() && !isAutoLoading
                    val sendBg: Brush = when {
                        isGenerating -> SolidColor(ErrorRed.copy(alpha = 0.15f))
                        canSend      -> Brush.linearGradient(listOf(c.accent, c.accentB))
                        else         -> SolidColor(c.surf3)
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(sendBg)
                            .border(
                                1.dp,
                                when {
                                    isGenerating -> ErrorRed.copy(alpha = 0.4f)
                                    canSend      -> c.accent.copy(0.5f)
                                    else         -> c.border
                                },
                                CircleShape,
                            )
                            .clickable(enabled = isGenerating || canSend) {
                                if (isGenerating) onStop() else onSend()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState  = isGenerating,
                            transitionSpec = {
                                scaleIn(tween(140)) + fadeIn(tween(140)) togetherWith
                                scaleOut(tween(140)) + fadeOut(tween(140))
                            },
                            label = "send_stop_icon",
                        ) { generating ->
                            if (generating) {
                                Icon(Icons.Default.Stop, "Stop", tint = ErrorRed, modifier = Modifier.size(15.dp))
                            } else {
                                Icon(
                                    Icons.Default.Send, "Send",
                                    tint = if (canSend) (if (c.isDark) Color(0xFF001820) else Color.White) else c.txt3,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Collection selector row ───────────────────────────────────────────────────

@Composable
private fun CollectionSelectorRow(
    collectionName: String,
    isNoCollection: Boolean,
    isGenerating: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalAnvitColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.surf1)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Searching in", color = c.txt2, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(if (isNoCollection) c.surf2 else c.accentDim)
                .border(1.dp, if (isNoCollection) c.border else c.border2, RoundedCornerShape(100.dp))
                .clickable(enabled = !isGenerating) { onClick() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    if (isNoCollection) Icons.Default.FolderOff else Icons.Default.Folder,
                    null,
                    tint     = if (isNoCollection) c.txt2 else c.accent,
                    modifier = Modifier.size(11.dp),
                )
                AnimatedContent(
                    targetState = collectionName,
                    transitionSpec = {
                        (slideInVertically(tween(160)) { it / 2 } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(160)) { -it / 2 } + fadeOut(tween(160)))
                    },
                    label = "selector_collection_name",
                ) { name ->
                    Text(
                        name,
                        color      = if (isNoCollection) c.txt2 else c.accent,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Icon(Icons.Default.ArrowDropDown, null, tint = if (isNoCollection) c.txt2 else c.accent, modifier = Modifier.size(14.dp))
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
    onDismiss: () -> Unit,
) {
    val c = LocalAnvitColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = c.surf2,
        dragHandle = {
            Box(
                modifier = Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.border2),
            )
        },
    ) {
        Text(
            "Search in…",
            color      = c.txt0,
            fontSize   = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 8.dp))
        CollectionPickerRow(
            icon = Icons.Default.FolderOff, name = "No collection",
            subtitle = "Answer directly, no documents", isSelected = currentId == null,
            iconTint = c.txt2, onClick = { onSelect(null) },
        )
        collections.forEach { coll ->
            CollectionPickerRow(
                icon = Icons.Default.Folder, name = coll.name,
                subtitle = coll.description.takeIf { it.isNotBlank() },
                isSelected = currentId == coll.id, iconTint = c.accent,
                onClick = { onSelect(coll.id) },
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
    onClick: () -> Unit,
) {
    val c = LocalAnvitColors.current
    val rowBg = if (isSelected) c.accentDim else Color.Transparent
    Row(
        modifier = Modifier.fillMaxWidth().background(rowBg).clickable { onClick() }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadioButton(
            selected = isSelected,
            onClick  = { onClick() },
            colors   = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.txt2),
        )
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = c.txt0, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            if (!subtitle.isNullOrBlank()) { Text(subtitle, color = c.txt2, fontSize = 11.sp) }
        }
        if (isSelected) { Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(16.dp)) }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: ChatMessage,
    userEmail: String = "",
    onEditQuery: ((String) -> Unit)? = null,
    onRestartFrom: (() -> Unit)? = null,
    onRestartGeneration: (() -> Unit)? = null,
    onReportResponse: ((String, String, String) -> Unit)? = null,
) {
    val c = LocalAnvitColors.current
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showEditDialog   by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var reportReason     by remember { mutableStateOf("") }
    var reportEmail      by remember(userEmail) { mutableStateOf(userEmail) }

    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
    ) {
        if (!isUser && message.isStreaming && (message.agentSteps.isNotEmpty() || message.thinkingContent.isNotEmpty())) {
            StreamingDots()
        }

        if (!isUser && message.agentSteps.isNotEmpty()) {
            AgentStepsPanel(message.agentSteps, modifier = Modifier.padding(bottom = 6.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Column(modifier = if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.weight(1f)) {

                // Thinking section
                if (!isUser && message.thinkingContent.isNotEmpty()) {
                    ThinkingSection(content = message.thinkingContent, isStreaming = message.isStreaming && message.content.isEmpty())
                    Spacer(Modifier.height(6.dp))
                }

                // Main content
                if (message.content.isNotEmpty() || (message.isStreaming && message.thinkingContent.isEmpty())) {
                    if (isUser) {
                        Column(horizontalAlignment = Alignment.End) {
                            if (message.content.isNotEmpty()) {
                                // User bubble: accent-tinted left border
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                                        .background(c.surf2)
                                        .border(1.dp, c.border2, RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                                ) {
                                    Text(message.content, color = c.txt0, fontSize = 14.sp, lineHeight = 20.sp)
                                }
                            }
                            if (!message.isStreaming) {
                                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (onRestartFrom != null) {
                                        IconButton(onClick = onRestartFrom, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Default.Replay, contentDescription = "Restart", tint = c.txt2, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    if (onEditQuery != null) {
                                        IconButton(onClick = { showEditDialog = true }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Default.Edit, "Edit", tint = c.txt2, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    IconButton(
                                        onClick  = { clipboardManager.setText(AnnotatedString(message.content)) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.ContentCopy, "Copy", tint = c.txt2, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val displayContent = if (message.wasStopped && message.content == com.anvit.localai.ui.viewmodels.ChatViewModel.STOPPED_MESSAGE_SENTINEL) "" else message.content
                            if (displayContent.isNotEmpty()) {
                                MarkdownText(text = displayContent)
                            }
                            if (message.isStreaming && message.content.isEmpty() && message.thinkingContent.isEmpty() && message.agentSteps.isEmpty()) {
                                StreamingDots()
                            }
                        }
                    }
                }

                if (!isUser && message.usedDirectMode) {
                    Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.Chat, null, tint = c.txt3, modifier = Modifier.size(10.dp))
                        Text("Direct chat", color = c.txt3, fontSize = 10.sp)
                    }
                }

                if (!isUser && !message.isStreaming && (message.content.isNotEmpty() || message.wasStopped)) {
                    Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (message.content.isNotEmpty()) {
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.ContentCopy, "Copy response", tint = c.txt2, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (onReportResponse != null) {
                            IconButton(onClick = { showReportDialog = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Flag, "Report", tint = c.txt2, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (message.wasStopped && onRestartGeneration != null) {
                            IconButton(onClick = onRestartGeneration, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = WarningAmber, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (!isUser && message.usedSources.isNotEmpty()) {
                    SourcesPanel(message.usedSources)
                }

                val timeStr = remember(message.id) {
                    val ldt = Instant.fromEpochMilliseconds(currentTimeMillis()).toLocalDateTime(TimeZone.currentSystemDefault())
                    "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 3.dp).then(if (isUser) Modifier.align(Alignment.End) else Modifier),
                ) {
                    Text(timeStr, color = c.txt1, fontSize = 11.sp)
                    if (!isUser && !message.isStreaming) {
                        Spacer(Modifier.width(8.dp))
                        Text("Generated by Anvit AI · Ref: ${message.provenanceId}", color = c.txt1, fontSize = 10.sp)
                    }
                }

                if (!isUser && message.wasStopped) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(WarningAmber.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Response incomplete",
                            color = WarningAmber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        if (onRestartGeneration != null) {
                            IconButton(onClick = onRestartGeneration, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = WarningAmber, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
            if (isUser) Spacer(Modifier.width(4.dp))
        }
    }

    if (showEditDialog) {
        EditQueryDialog(
            initialText = message.content,
            onConfirm   = { newText -> onEditQuery?.invoke(newText); showEditDialog = false },
            onDismiss   = { showEditDialog = false },
        )
    }

    if (showReportDialog) {
        val c2 = LocalAnvitColors.current
        val emailMissing = userEmail.isBlank()
        var emailError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor   = c2.surf2,
            title = { Text("Report Response", color = c2.txt0) },
            text  = {
                Column {
                    Text("Does this response contain offensive or unsafe content?", color = c2.txt1, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    if (emailMissing) {
                        OutlinedTextField(
                            value = reportEmail, onValueChange = { reportEmail = it; emailError = false },
                            placeholder = { Text("Your email (required)…", color = c2.txt3) },
                            isError = emailError,
                            supportingText = if (emailError) { { Text("Email is required", color = ErrorRed, fontSize = 11.sp) } } else null,
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c2.txt0, unfocusedTextColor = c2.txt0, focusedBorderColor = c2.accent, unfocusedBorderColor = c2.border2, cursorColor = c2.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = reportReason, onValueChange = { reportReason = it },
                        placeholder = { Text("Reason (optional)…", color = c2.txt3) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c2.txt0, unfocusedTextColor = c2.txt0, focusedBorderColor = c2.accent, unfocusedBorderColor = c2.border2, cursorColor = c2.accent),
                        modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (emailMissing && reportEmail.isBlank()) { emailError = true }
                    else {
                        onReportResponse?.invoke(if (emailMissing) reportEmail else userEmail, reportReason, message.content)
                        showReportDialog = false; reportReason = ""
                    }
                }) { Text("Submit", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel", color = c2.txt2) }
            },
        )
    }
}

// ── Edit query dialog ─────────────────────────────────────────────────────────

@Composable
private fun EditQueryDialog(initialText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalAnvitColors.current
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.surf2,
        title = { Text("Edit message", color = c.txt0) },
        text  = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.border2,
                    focusedTextColor = c.txt0, unfocusedTextColor = c.txt0, cursorColor = c.accent,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            )
        },
        confirmButton = {
            Button(
                onClick  = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled  = text.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.isDark) Color(0xFF001820) else Color.White),
            ) {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.txt2) } },
    )
}

// ── Sources panel ─────────────────────────────────────────────────────────────

@Composable
private fun SourcesPanel(sources: List<SourceChunk>) {
    val c = LocalAnvitColors.current
    val sorted   = remember(sources) { sources.sortedByDescending { it.score } }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.Default.Description, null, tint = c.accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text("Sources", color = c.txt1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            Text("(${sorted.size})", color = c.txt2, fontSize = 11.sp)
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(sorted) { index, source ->
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surf1)
                        .border(1.dp, c.border2, RoundedCornerShape(12.dp))
                        .clickable { selectedIndex = index }
                        .padding(10.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, null, tint = c.accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(source.title, color = c.txt0, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(source.snippet, color = c.txt1, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                    }
                }
            }
        }
    }

    val idx = selectedIndex
    if (idx != null) {
        val source = sorted[idx]
        AlertDialog(
            onDismissRequest = { selectedIndex = null },
            containerColor   = c.surf2,
            title = {
                Text(source.title, color = c.txt0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
            },
            text  = {
                Box(modifier = Modifier.height(300.dp)) {
                    val scrollState = rememberScrollState()
                    Text(
                        source.snippet,
                        color = c.txt1,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.verticalScroll(scrollState),
                    )
                }
            },
            confirmButton = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    IconButton(
                        onClick = { selectedIndex = (idx - 1).coerceAtLeast(0) },
                        enabled = idx > 0,
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous", tint = if (idx > 0) c.accent else c.txt2)
                    }
                    Text("${idx + 1} / ${sorted.size}", color = c.txt2, fontSize = 12.sp)
                    IconButton(
                        onClick = { selectedIndex = (idx + 1).coerceAtMost(sorted.size - 1) },
                        enabled = idx < sorted.size - 1,
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next", tint = if (idx < sorted.size - 1) c.accent else c.txt2)
                    }
                    TextButton(onClick = { selectedIndex = null }) {
                        Text("Close", color = c.accent)
                    }
                }
            },
        )
    }
}

// ── Thinking section ──────────────────────────────────────────────────────────

@Composable
private fun ThinkingSection(content: String, isStreaming: Boolean) {
    val c = LocalAnvitColors.current
    var expanded by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) { if (isStreaming) expanded = true }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.amberDim)
            .border(1.dp, c.amber.copy(0.3f), RoundedCornerShape(12.dp)),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Lightbulb, null, tint = c.amber, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Thinking", color = c.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = c.amber, modifier = Modifier.size(15.dp))
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                MarkdownText(
                    text     = content,
                    color    = c.txt1,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}

// ── Streaming dots ────────────────────────────────────────────────────────────

@Composable
private fun StreamingDots() {
    val c = LocalAnvitColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple_scale",
    )
    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple_alpha",
    )
    val coreScale by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "core_scale",
    )

    Box(
        modifier = Modifier.padding(vertical = 4.dp).size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val maxRadius = size.minDimension / 2f
            // ripple ring
            drawCircle(
                color = c.accent.copy(alpha = rippleAlpha),
                radius = maxRadius * rippleScale,
                center = center,
            )
            // solid core
            drawCircle(
                color = c.accent,
                radius = maxRadius * 0.38f * coreScale,
                center = center,
            )
        }
    }
}

// ── Crossed pin icon ──────────────────────────────────────────────────────────

@Composable
private fun CrossedPinIcon(tint: Color, modifier: Modifier = Modifier, crossed: Boolean = true) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.PushPin, null, tint = tint, modifier = Modifier.fillMaxSize())
        if (crossed) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(
                    color       = tint,
                    start       = Offset(0f, size.height),
                    end         = Offset(size.width, 0f),
                    strokeWidth = 2.dp.toPx(),
                    cap         = StrokeCap.Round,
                )
            }
        }
    }
}

// ── Time helpers ──────────────────────────────────────────────────────────────

private fun groupSessionsByTime(sessions: List<ChatSessionEntity>): List<Pair<String, List<ChatSessionEntity>>> {
    val now   = Instant.fromEpochMilliseconds(currentTimeMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date
    val today = now
    val yest  = now.minus(1, DateTimeUnit.DAY)
    val groups = linkedMapOf<String, MutableList<ChatSessionEntity>>()
    sessions.forEach { s ->
        val d = Instant.fromEpochMilliseconds(s.updatedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val label = when (d) {
            today -> "Today"
            yest  -> "Yesterday"
            else  -> "Earlier"
        }
        groups.getOrPut(label) { mutableListOf() }.add(s)
    }
    return groups.entries.map { it.key to it.value }
}

private fun formatRelativeTime(epochMs: Long): String {
    val now  = currentTimeMillis()
    val diff: Long = now - epochMs
    val dayMs = 86_400_000L
    return when {
        diff < 60_000L          -> "just now"
        diff < 3_600_000L       -> "${diff / 60_000}m ago"
        diff < 86_400_000L      -> "${diff / 3_600_000}h ago"
        diff < 7L * dayMs       -> "${diff / dayMs}d ago"
        else -> {
            val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
            "${ldt.dayOfMonth} ${ldt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
        }
    }
}

private fun Modifier.onFocusChange(block: (FocusState) -> Unit): Modifier =
    this.onFocusChanged(block)

// ── Model Picker Sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<GemmaModel>,
    loadedModelName: String,
    isSwitching: Boolean,
    isModelFilePresent: (String) -> Boolean,
    onLoad: (GemmaModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalAnvitColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = c.surf2,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(c.border2),
            )
        },
    ) {
        Text(
            "Switch Model",
            color      = c.txt0,
            fontSize   = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 8.dp))

        models.forEach { model ->
            val filePresent = isModelFilePresent(model.fileName)
            val isActive    = model.displayName == loadedModelName
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isActive) c.accentDim else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        color      = c.txt0,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        model.ramRequired,
                        color    = c.txt1,
                        fontSize = 11.sp,
                    )
                }
                when {
                    isActive -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint     = c.green,
                            modifier = Modifier.size(15.dp),
                        )
                        Text("Active", color = c.green, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    isSwitching -> CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        color       = c.accent,
                        strokeWidth = 2.dp,
                    )
                    filePresent -> Button(
                        onClick  = { onLoad(model) },
                        enabled  = !isSwitching,
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = c.accent,
                            contentColor   = if (c.isDark) androidx.compose.ui.graphics.Color(0xFF060A0F) else androidx.compose.ui.graphics.Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("Load", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    else -> Text(
                        "Not downloaded",
                        color    = c.txt2,
                        fontSize = 11.sp,
                    )
                }
            }
            HorizontalDivider(color = c.border, thickness = 0.5.dp)
        }
        Spacer(Modifier.height(32.dp))
    }
}
