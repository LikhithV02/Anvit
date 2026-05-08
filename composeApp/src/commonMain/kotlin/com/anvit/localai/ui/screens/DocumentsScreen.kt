package com.anvit.localai.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.anvit.localai.data.db.entities.CollectionEntity
import com.anvit.localai.data.db.entities.DocumentEntity
import com.anvit.localai.ui.components.rememberPdfPicker
import com.anvit.localai.ui.theme.*
import com.anvit.localai.ui.viewmodels.DocumentsViewModel
import org.koin.compose.viewmodel.koinViewModel

// ── Root screen ───────────────────────────────────────────────────────────────

@Composable
fun DocumentsScreen(viewModel: DocumentsViewModel = koinViewModel()) {
    val c = LocalAnvitColors.current
    val uiState = viewModel.uiState.collectAsState().value

    val pickPdf = rememberPdfPicker { fileName, bytes ->
        viewModel.ingestDocument(fileName, bytes)
    }

    var showCreateCollectionDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExpandableFab(
                onAddFolder = { showCreateCollectionDialog = true },
                onAddPdf    = { pickPdf() }
            )
        },
        containerColor = c.bg
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            DocumentsTopBar(
                collectionName = uiState.activeCollectionName,
                documentCount  = uiState.documents.size
            )

            AnimatedVisibility(visible = uiState.errorMessage != null) {
                uiState.errorMessage?.let {
                    StatusBanner(text = it, isError = true, onDismiss = { viewModel.clearMessages() })
                    LaunchedEffect(it) { delay(5000); viewModel.clearMessages() }
                }
            }
            AnimatedVisibility(visible = uiState.successMessage != null) {
                uiState.successMessage?.let {
                    StatusBanner(text = it, isError = false, onDismiss = { viewModel.clearMessages() })
                    LaunchedEffect(it) { delay(4000); viewModel.clearMessages() }
                }
            }

            AnimatedVisibility(visible = uiState.isIngesting) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = c.surf2
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                            Text(uiState.ingestionProgress.ifEmpty { "Processing PDF…" }, color = c.txt0, fontSize = 13.sp)
                        }
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                            color = c.accent, trackColor = c.border
                        )
                    }
                }
            }

            CollectionsRow(
                collections        = uiState.collections,
                activeCollectionId = uiState.activeCollectionId,
                onSelect           = { viewModel.setActiveCollection(it) },
                onRename           = { id, name -> viewModel.renameCollection(id, name) },
                onDelete           = { viewModel.deleteCollection(it) }
            )

            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            if (uiState.documents.isEmpty() && !uiState.isIngesting) {
                DocumentsEmptyState(
                    collectionName = uiState.activeCollectionName,
                    modifier       = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                val totalChunks = uiState.documents.sumOf { it.chunkCount }
                val allReady    = uiState.documents.all { it.status == "READY" }
                LazyColumn(
                    modifier        = Modifier.weight(1f),
                    contentPadding  = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.documents, key = { it.id }) { doc ->
                        SwipeableDocumentCard(doc = doc, onDelete = { viewModel.deleteDocument(doc) })
                    }
                    item(key = "stats_footer") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.surf2)
                                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Layers, null, tint = c.accent, modifier = Modifier.size(14.dp))
                                Text("$totalChunks total chunks indexed", color = c.txt1, fontSize = 12.sp)
                            }
                            Text(
                                if (allReady) "Ready for Q&A" else "Indexing…",
                                color      = if (allReady) SuccessGreen else c.amber,
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onConfirm = { name, desc ->
                viewModel.createCollection(name, desc)
                showCreateCollectionDialog = false
            },
            onDismiss = { showCreateCollectionDialog = false }
        )
    }
}

@Composable
private fun ExpandableFab(onAddFolder: () -> Unit, onAddPdf: () -> Unit) {
    val c = LocalAnvitColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(visible = expanded) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("New Folder", modifier = Modifier.padding(end = 12.dp), color = c.txt0, fontWeight = FontWeight.Medium)
                    SmallFloatingActionButton(onClick = { expanded = false; onAddFolder() }, containerColor = c.surf2, contentColor = c.accent) {
                        Icon(Icons.Default.Folder, contentDescription = "New Folder")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Upload PDF", modifier = Modifier.padding(end = 12.dp), color = c.txt0, fontWeight = FontWeight.Medium)
                    SmallFloatingActionButton(onClick = { expanded = false; onAddPdf() }, containerColor = c.surf2, contentColor = c.accent) {
                        Icon(Icons.Default.Description, contentDescription = "Upload PDF")
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = c.accent,
            contentColor = if (c.isDark) androidx.compose.ui.graphics.Color(0xFF060A0F) else androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(if (expanded) Icons.Default.Close else Icons.Default.Add, contentDescription = "Expand")
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun DocumentsTopBar(collectionName: String, documentCount: Int) {
    val c = LocalAnvitColors.current
    Surface(color = c.surf1, shadowElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(c.accentDim)
                    .border(1.dp, c.border2, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Documents", color = c.txt0, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                Text(
                    if (documentCount == 0) "No files · $collectionName"
                    else "$documentCount ${if (documentCount == 1) "file" else "files"} · $collectionName",
                    color = c.txt1, fontSize = 12.sp
                )
            }
        }
    }
    HorizontalDivider(color = c.border, thickness = 0.5.dp)
}

// ── Collections row ───────────────────────────────────────────────────────────

@Composable
private fun CollectionsRow(
    collections: List<CollectionEntity>,
    activeCollectionId: String,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    val c = LocalAnvitColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth().background(c.surf1)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        collections.forEach { collection ->
            CollectionChip(
                collection = collection,
                isActive   = collection.id == activeCollectionId,
                onSelect   = { onSelect(collection.id) },
                onRename   = { newName -> onRename(collection.id, newName) },
                onDelete   = { onDelete(collection.id) }
            )
        }
    }
}

@Composable
private fun CollectionChip(
    collection: CollectionEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    val c = LocalAnvitColors.current
    var menuExpanded      by remember { mutableStateOf(false) }
    var showRenameDialog  by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val bgColor     = if (isActive) c.accentDim else c.surf3
    val borderColor = if (isActive) c.border2   else c.border
    val textColor   = if (isActive) c.accent     else c.txt1

    Box {
        Surface(
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onSelect() },
            shape = RoundedCornerShape(20.dp), color = bgColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 12.dp,
                    end   = if (collection.isDefault) 12.dp else 4.dp,
                    top = 6.dp, bottom = 6.dp
                ),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isActive) Icon(Icons.Default.FolderOpen, null, tint = c.accent, modifier = Modifier.size(14.dp))
                Text(collection.name, color = textColor, fontSize = 13.sp, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                if (!collection.isDefault) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, null, tint = textColor.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        if (!collection.isDefault) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, containerColor = c.surf3) {
                DropdownMenuItem(
                    text = { Text("Rename", color = c.txt0, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = c.txt1, modifier = Modifier.size(16.dp)) },
                    onClick = { menuExpanded = false; showRenameDialog = true }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = ErrorRed, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ErrorRed, modifier = Modifier.size(16.dp)) },
                    onClick = { menuExpanded = false; showDeleteConfirm = true }
                )
            }
        }
    }

    if (showRenameDialog) {
        var renameText by remember(collection.name) { mutableStateOf(collection.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = c.surf2,
            title   = { Text("Rename Folder", color = c.txt0) },
            text    = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.border2, focusedTextColor = c.txt0, unfocusedTextColor = c.txt0)
                )
            },
            confirmButton = { TextButton(onClick = { onRename(renameText); showRenameDialog = false }) { Text("Save", color = c.accent) } },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = c.txt1) } }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = c.surf2,
            title   = { Text("Delete Folder?", color = c.txt0) },
            text    = { Text("\"${collection.name}\" and all its files will be permanently deleted.", color = c.txt1) },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("Delete", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = c.txt1) } }
        )
    }
}

// ── Create collection dialog ──────────────────────────────────────────────────

@Composable
private fun CreateCollectionDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalAnvitColors.current
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surf2,
        title = { Text("New Folder", color = c.txt0) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name", color = c.txt2) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.border2, focusedTextColor = c.txt0, unfocusedTextColor = c.txt0, focusedLabelColor = c.accent, unfocusedLabelColor = c.txt2))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description (optional)", color = c.txt2) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.border2, focusedTextColor = c.txt0, unfocusedTextColor = c.txt0, focusedLabelColor = c.accent, unfocusedLabelColor = c.txt2))
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onConfirm(name, desc) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.isDark) androidx.compose.ui.graphics.Color(0xFF060A0F) else androidx.compose.ui.graphics.Color.White)) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.txt1) } }
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun DocumentsEmptyState(collectionName: String, modifier: Modifier = Modifier) {
    val c = LocalAnvitColors.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.accentDim)
                    .border(1.dp, c.border2, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.FolderOpen, null, tint = c.accent, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("This folder is empty", color = c.txt0, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Tap + to add a PDF to \"$collectionName\"", color = c.txt2, fontSize = 13.sp)
        }
    }
}

// ── Status banner ─────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val bgColor   = if (isError) ErrorRed.copy(alpha = 0.12f) else SuccessGreen.copy(alpha = 0.12f)
    val textColor = if (isError) ErrorRed else SuccessGreen
    Row(
        modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (isError) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = textColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = textColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, null, tint = textColor, modifier = Modifier.size(14.dp))
        }
    }
}

// ── Swipeable document card ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableDocumentCard(doc: DocumentEntity, onDelete: () -> Unit) {
    val c = LocalAnvitColors.current
    val scope      = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { showConfirm = true; false } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val alpha = (dismissState.progress * 2f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(ErrorRed.copy(alpha = alpha * 0.85f)).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = alpha), modifier = Modifier.size(22.dp))
                    if (alpha > 0.6f) Text("Delete", color = androidx.compose.ui.graphics.Color.White.copy(alpha = alpha), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    ) {
        DocumentCard(doc = doc, onDelete = { showConfirm = true })
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false; scope.launch { dismissState.reset() } },
            containerColor = c.surf2,
            title   = { Text("Delete document?", color = c.txt0) },
            text    = { Text("\"${doc.fileName}\" will be removed from this folder.", color = c.txt1) },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Delete", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { showConfirm = false; scope.launch { dismissState.reset() } }) { Text("Cancel", color = c.txt1) } }
        )
    }
}

// ── Document card ─────────────────────────────────────────────────────────────

@Composable
private fun DocumentCard(doc: DocumentEntity, onDelete: () -> Unit) {
    val c = LocalAnvitColors.current
    val statusColor = when (doc.status) {
        "READY"      -> SuccessGreen
        "PROCESSING" -> c.accent
        "FAILED"     -> ErrorRed
        else         -> WarningAmber
    }
    val statusLabel = when (doc.status) {
        "READY"      -> "Ready"
        "PROCESSING" -> "Processing"
        "FAILED"     -> "Failed"
        else         -> doc.status
    }

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = c.surf2, border = BorderStroke(0.5.dp, c.border)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.accentDim).border(1.dp, c.border2, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, null, tint = c.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(doc.fileName, color = c.txt0, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(statusColor))
                        Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (doc.pageCount > 0) Text("· ${doc.pageCount}p", color = c.txt2, fontSize = 10.sp)
                    if (doc.chunkCount > 0) Text("· ${doc.chunkCount} chunks", color = c.txt2, fontSize = 10.sp)
                    if (doc.sizeBytes > 0) Text("· ${formatFileSize(doc.sizeBytes)}", color = c.txt2, fontSize = 10.sp)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Delete, "Delete", tint = ErrorRed.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024     -> "${bytes / 1_024} KB"
    else               -> "$bytes B"
}
