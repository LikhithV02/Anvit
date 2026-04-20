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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
        containerColor = Surface0
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            DocumentsTopBar(
                collectionName = uiState.activeCollectionName,
                documentCount  = uiState.documents.size
            )

            // ── Status banners ────────────────────────────────────────────────
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

            // ── Ingestion progress ────────────────────────────────────────────
            AnimatedVisibility(visible = uiState.isIngesting) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Surface2
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TealPrimary, strokeWidth = 2.dp)
                            Text(uiState.ingestionProgress.ifEmpty { "Processing PDF…" }, color = TextPrimary, fontSize = 13.sp)
                        }
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                            color = TealPrimary, trackColor = BorderSubtle
                        )
                    }
                }
            }

            // ── Collections row ───────────────────────────────────────────────
            CollectionsRow(
                collections        = uiState.collections,
                activeCollectionId = uiState.activeCollectionId,
                onSelect           = { viewModel.setActiveCollection(it) },
                onRename           = { id, name -> viewModel.renameCollection(id, name) },
                onDelete           = { viewModel.deleteCollection(it) }
            )

            HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

            // ── Document list ─────────────────────────────────────────────────
            if (uiState.documents.isEmpty() && !uiState.isIngesting) {
                DocumentsEmptyState(
                    collectionName = uiState.activeCollectionName,
                    modifier       = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier        = Modifier.weight(1f),
                    contentPadding  = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.documents, key = { it.id }) { doc ->
                        SwipeableDocumentCard(doc = doc, onDelete = { viewModel.deleteDocument(doc) })
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
    var expanded by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(visible = expanded) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("New Folder", modifier = Modifier.padding(end = 12.dp), color = TextPrimary, fontWeight = FontWeight.Medium)
                    SmallFloatingActionButton(onClick = { expanded = false; onAddFolder() }, containerColor = Surface2, contentColor = TealPrimary) {
                        Icon(Icons.Default.Folder, contentDescription = "New Folder")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Upload PDF", modifier = Modifier.padding(end = 12.dp), color = TextPrimary, fontWeight = FontWeight.Medium)
                    SmallFloatingActionButton(onClick = { expanded = false; onAddPdf() }, containerColor = Surface2, contentColor = TealPrimary) {
                        Icon(Icons.Default.Description, contentDescription = "Upload PDF")
                    }
                }
            }
        }
        FloatingActionButton(onClick = { expanded = !expanded }, containerColor = TealPrimary, contentColor = Surface0) {
            Icon(if (expanded) Icons.Default.Close else Icons.Default.Add, contentDescription = "Expand")
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun DocumentsTopBar(collectionName: String, documentCount: Int) {
    Surface(color = Surface1, shadowElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(TealPrimary.copy(alpha = 0.14f), TealDark.copy(alpha = 0.07f))))
                    .border(1.dp, TealPrimary.copy(alpha = 0.15f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, null, tint = TealPrimary, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Documents", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                Text(
                    if (documentCount == 0) "No files · $collectionName"
                    else "$documentCount ${if (documentCount == 1) "file" else "files"} · $collectionName",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
        }
    }
    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth().background(Surface1)
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
    var menuExpanded      by remember { mutableStateOf(false) }
    var showRenameDialog  by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val bgColor     = if (isActive) TealPrimary.copy(alpha = 0.18f) else Surface3
    val borderColor = if (isActive) TealPrimary.copy(alpha = 0.6f)  else BorderSubtle
    val textColor   = if (isActive) TealPrimary else TextSecondary

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
                if (isActive) Icon(Icons.Default.FolderOpen, null, tint = TealPrimary, modifier = Modifier.size(14.dp))
                Text(collection.name, color = textColor, fontSize = 13.sp, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                if (!collection.isDefault) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, null, tint = textColor.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        if (!collection.isDefault) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, containerColor = Surface3) {
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
        var renameText by remember(collection.name) { mutableStateOf(collection.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Surface2,
            title   = { Text("Rename Folder", color = TextPrimary) },
            text    = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary, unfocusedBorderColor = BorderDefault, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
            },
            confirmButton = { TextButton(onClick = { onRename(renameText); showRenameDialog = false }) { Text("Save", color = TealPrimary) } },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = TextSecondary) } }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Surface2,
            title   = { Text("Delete Folder?", color = TextPrimary) },
            text    = { Text("\"${collection.name}\" and all its files will be permanently deleted.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("Delete", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextSecondary) } }
        )
    }
}

// ── Create collection dialog ──────────────────────────────────────────────────

@Composable
private fun CreateCollectionDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("New Folder", color = TextPrimary) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name", color = TextHint) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary, unfocusedBorderColor = BorderDefault, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedLabelColor = TealPrimary, unfocusedLabelColor = TextHint))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description (optional)", color = TextHint) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary, unfocusedBorderColor = BorderDefault, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedLabelColor = TealPrimary, unfocusedLabelColor = TextHint))
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onConfirm(name, desc) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Surface0)) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun DocumentsEmptyState(collectionName: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Outlined.FolderOpen, null, tint = TextHint, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(4.dp))
            Text("This folder is empty", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Tap + to add a PDF to this folder", color = TextHint, fontSize = 13.sp)
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
                    Icon(Icons.Default.Delete, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = alpha), modifier = Modifier.size(22.dp))
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
            containerColor = Surface2,
            title   = { Text("Delete document?", color = TextPrimary) },
            text    = { Text("\"${doc.fileName}\" will be removed from this folder.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Delete", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { showConfirm = false; scope.launch { dismissState.reset() } }) { Text("Cancel", color = TextSecondary) } }
        )
    }
}

// ── Document card ─────────────────────────────────────────────────────────────

@Composable
private fun DocumentCard(doc: DocumentEntity, onDelete: () -> Unit) {
    val statusColor = when (doc.status) {
        "READY"      -> SuccessGreen
        "PROCESSING" -> TealPrimary
        "FAILED"     -> ErrorRed
        else         -> WarningAmber
    }
    val statusLabel = when (doc.status) {
        "READY"      -> "Ready"
        "PROCESSING" -> "Processing"
        "FAILED"     -> "Failed"
        else         -> doc.status
    }

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Surface2, border = BorderStroke(0.5.dp, BorderSubtle)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(TealDark.copy(0.3f), TealPrimary.copy(0.15f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, null, tint = TealPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(doc.fileName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = statusColor.copy(alpha = 0.15f)) {
                        Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(statusColor))
                            Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (doc.pageCount > 0) Text("${doc.pageCount}p", color = TextHint, fontSize = 10.sp)
                    if (doc.chunkCount > 0) Text("${doc.chunkCount} chunks", color = TextHint, fontSize = 10.sp)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "Delete", tint = ErrorRed.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }
    }
}
