package com.anvit.localai.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Policy
import androidx.navigation.NavController
import com.anvit.localai.data.models.EmbeddingModelInfo
import com.anvit.localai.data.models.EmbeddingModels
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.download.DownloadProgress
import com.anvit.localai.download.DownloadState
import com.anvit.localai.ui.theme.*
import com.anvit.localai.ui.contextWindowSettingMax
import com.anvit.localai.ui.viewmodels.SettingsViewModel
import com.anvit.localai.utils.isIosPlatform
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = koinViewModel()) {
    val c = LocalAnvitColors.current
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Settings", color = c.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSection(title = "Appearance") {
            Text("Theme", color = c.txt1, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppearanceChip(
                    label = "System",
                    icon = Icons.Outlined.SettingsBrightness,
                    selected = uiState.themeMode == "system",
                    onClick = { viewModel.setThemeMode("system") },
                    modifier = Modifier.weight(1f)
                )
                AppearanceChip(
                    label = "Light",
                    icon = Icons.Outlined.LightMode,
                    selected = uiState.themeMode == "light",
                    onClick = { viewModel.setThemeMode("light") },
                    modifier = Modifier.weight(1f)
                )
                AppearanceChip(
                    label = "Dark",
                    icon = Icons.Outlined.DarkMode,
                    selected = uiState.themeMode == "dark",
                    onClick = { viewModel.setThemeMode("dark") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── Gemma Model Selection ──────────────────────────────────────────────
        SettingsSection(title = "Gemma Model") {
            viewModel.availableModels.forEachIndexed { index, model ->
                val download = uiState.downloads[model.id]
                val filePresent = remember(uiState.downloads, uiState.deletionTick) {
                    viewModel.isModelFilePresent(model.fileName)
                }

                GemmaModelRow(
                    model = model,
                    filePresent = filePresent,
                    download = download,
                    onDownload = { viewModel.downloadGemmaModel(model) },
                    onPause = { viewModel.pauseDownload(model.id) },
                    onCancel = { viewModel.cancelDownload(model.id) },
                    onDelete = { viewModel.deleteModel(model.fileName, model.id) },
                    formatBytes = viewModel::formatBytes
                )
                if (index < viewModel.availableModels.lastIndex) {
                    HorizontalDivider(color = c.border, thickness = 0.5.dp)
                }
            }

            Spacer(Modifier.height(4.dp))
        }

        // ── Embedding Model ────────────────────────────────────────────────────
        SettingsSection(title = "Embedding Model") {
            Text(
                "Used for document search. Download one to enable Agentic RAG.",
                color = c.txt1, fontSize = 11.sp, lineHeight = 16.sp
            )
            EmbeddingModels.all.forEachIndexed { index, model ->
                val download = uiState.downloads[model.id]
                val filePresent = remember(uiState.downloads, uiState.deletionTick) {
                    viewModel.isModelFilePresent(model.fileName)
                }
                if (index > 0) HorizontalDivider(color = c.border, thickness = 0.5.dp)
                EmbeddingModelRow(
                    model = model,
                    filePresent = filePresent,
                    download = download,
                    onDownload = { viewModel.downloadEmbeddingModel(model) },
                    onPause = { viewModel.pauseDownload(model.id) },
                    onCancel = { viewModel.cancelDownload(model.id) },
                    onDelete = { viewModel.deleteModel(model.fileName, model.id) },
                    formatBytes = viewModel::formatBytes
                )
            }

            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isActive = uiState.embeddingModelStatus == "Active"
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        uiState.embeddingModelStatus,
                        color = if (isActive) c.green else c.txt1,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = { viewModel.initializeEmbedding() },
                    enabled = uiState.embeddingModelStatus != "Active" && uiState.embeddingModelStatus != "Activating…",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.accentDim,
                        contentColor = c.accent,
                        disabledContainerColor = c.accentDim.copy(alpha = 0.4f),
                        disabledContentColor = c.accent.copy(alpha = 0.4f)
                    )
                ) {
                    Text("Activate", fontSize = 12.sp)
                }
            }
        }

        // ── User Information ──────────────────────────────────────────────────
        SettingsSection(title = "User Information") {
            Text(
                "Your email is used only for reporting issues.",
                color = c.txt1, fontSize = 11.sp
            )
            OutlinedTextField(
                value = uiState.userEmail,
                onValueChange = { viewModel.setUserEmail(it) },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                placeholder = { Text("email@example.com", color = c.txt2, fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                leadingIcon = {
                    Icon(Icons.Default.Email, null, tint = c.accent.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.border2,
                    focusedTextColor = c.txt0,
                    unfocusedTextColor = c.txt0,
                    cursorColor = c.accent
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }

        // ── HuggingFace Token ─────────────────────────────────────────────────
        HuggingFaceTokenSection(
            token = uiState.huggingFaceToken,
            isSaved = uiState.hfTokenSaved,
            onTokenChange = viewModel::setHuggingFaceToken,
            onSave = viewModel::saveHuggingFaceToken
        )

        // ── Agentic RAG ────────────────────────────────────────────────────────
        SettingsSection(title = "Agentic RAG") {
            SwitchRow(
                "Enable Agentic RAG", "Full multi-step retrieval pipeline",
                uiState.enableAgenticRag
            ) { viewModel.setEnableAgenticRag(it) }
            SwitchRow(
                "Self-Critique Loop", "Evaluate and refine responses",
                uiState.enableSelfCritique
            ) { viewModel.setEnableSelfCritique(it) }

            Spacer(Modifier.height(2.dp))
            Text("Retrieval Mode", color = c.txt1, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("vector", "bm25", "hybrid").forEach { mode ->
                    FilterChip(
                        selected = uiState.retrievalMode == mode,
                        onClick = { viewModel.setRetrievalMode(mode) },
                        label = { Text(mode.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = c.accentDim,
                            selectedLabelColor = c.accent,
                            labelColor = c.txt1
                        )
                    )
                }
            }

            LabeledSlider(
                label = "Max Retrieval Chunks",
                value = uiState.maxRetrievalChunks.toFloat(),
                displayValue = "${uiState.maxRetrievalChunks}",
                onValueChange = { viewModel.setMaxRetrievalChunks(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8
            )
        }

        // ── Generation Parameters ─────────────────────────────────────────────
        SettingsSection(title = "Generation Parameters") {
            if (!isIosPlatform()) {
                Text("Accelerator", color = c.txt1, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("cpu", "gpu").forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = uiState.accelerator == option,
                            onClick = { viewModel.setAccelerator(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = c.accentDim,
                                activeContentColor = c.accent,
                                inactiveContentColor = c.txt1
                            )
                        ) {
                            Text(option.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (uiState.accelerator == "gpu") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "GPU is experimental — may crash on some devices. Reload model after switching.",
                        color = WarningAmber,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                val hasModel = viewModel.availableModels.any { viewModel.isModelFilePresent(it.fileName) }
                Button(
                    onClick = { viewModel.loadSelectedModel() },
                    enabled = hasModel && !uiState.isLoadingModel,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.accentDim,
                        contentColor = c.accent,
                        disabledContainerColor = c.accentDim.copy(alpha = 0.4f),
                        disabledContentColor = c.accent.copy(alpha = 0.4f)
                    )
                ) {
                    if (uiState.isLoadingModel) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = c.accent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Loading…", fontSize = 12.sp)
                    } else {
                        Text("Reload Model", fontSize = 12.sp)
                    }
                }
                uiState.modelLoadSuccess?.let {
                    Text(it, color = SuccessGreen, fontSize = 11.sp)
                }
                uiState.modelLoadError?.let {
                    Text(it, color = ErrorRed, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(4.dp))

            LabeledSlider(
                label = "Temperature",
                value = uiState.temperature,
                displayValue = (kotlin.math.round(uiState.temperature * 100) / 100.0).toString().let {
                    if ('.' !in it) "$it.00"
                    else it.substringBefore('.') + "." + it.substringAfter('.').padEnd(2, '0').take(2)
                },
                onValueChange = { viewModel.setTemperature(it) },
                valueRange = 0.0f..2.0f
            )

            LabeledSlider(
                label = "Top-K",
                value = uiState.topK.toFloat(),
                displayValue = "${uiState.topK}",
                onValueChange = { viewModel.setTopK(it.toInt()) },
                valueRange = 1f..100f
            )

            val selectedContextMax = viewModel.availableModels
                .find { it.id == uiState.selectedModelId }
                ?.contextWindowSize
                ?: 128000
            val contextWindowMax = contextWindowSettingMax(isIosPlatform(), selectedContextMax)
            LabeledSlider(
                label = "Context Window",
                value = uiState.contextWindow.coerceAtMost(contextWindowMax).toFloat(),
                displayValue = "${uiState.contextWindow.coerceAtMost(contextWindowMax)}",
                onValueChange = { viewModel.setContextWindow(it.toInt()) },
                valueRange = 1024f..contextWindowMax.toFloat(),
                steps = 30
            )

            LabeledSlider(
                label = "Max Output Tokens",
                value = uiState.maxOutputTokens.toFloat(),
                displayValue = "${uiState.maxOutputTokens}",
                onValueChange = { viewModel.setMaxOutputTokens(it.toInt()) },
                valueRange = 100f..8192f,
                steps = 30
            )

            HorizontalDivider(color = c.border, thickness = 0.5.dp)
            TextButton(
                onClick = { viewModel.resetGenerationDefaults() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = c.txt1)
            ) {
                Text("Reset to defaults", fontSize = 12.sp)
            }
        }

        // ── About ──────────────────────────────────────────────────────────────
        SettingsSection(title = "About") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Anvit Local AI", color = c.txt0, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("v1.0.0", color = c.txt2, fontSize = 12.sp)
            }
            HorizontalDivider(color = c.border, thickness = 0.5.dp)
            // About Anvit row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { navController.navigate("about") }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Info, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Text("About Anvit", color = c.txt0, fontSize = 14.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = c.txt2, modifier = Modifier.size(18.dp))
            }
            HorizontalDivider(color = c.border, thickness = 0.5.dp)
            // Privacy Policy row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { navController.navigate("privacy") }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Policy, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Text("Privacy Policy", color = c.txt0, fontSize = 14.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = c.txt2, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Appearance chip ───────────────────────────────────────────────────────────

@Composable
private fun AppearanceChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAnvitColors.current
    val bgColor     = if (selected) c.accentDim else c.surf2
    val borderColor = if (selected) c.border2   else c.border
    val textColor   = if (selected) c.accent     else c.txt1

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(18.dp))
            Text(label, color = textColor, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

// ── Gemma model row ───────────────────────────────────────────────────────────

@Composable
private fun GemmaModelRow(
    model: GemmaModel,
    filePresent: Boolean,
    download: DownloadProgress?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String
) {
    val c = LocalAnvitColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                model.displayName,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                color = c.txt0, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Text(
                model.sizeLabel,
                color = c.txt2, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
        Text(
            model.ramRequired,
            modifier = Modifier.padding(start = 4.dp),
            color = c.txt1, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        DownloadRow(
            filePresent = filePresent,
            download = download,
            sizeBytes = model.sizeBytes,
            onDownload = onDownload,
            onPause = onPause,
            onCancel = onCancel,
            onDelete = onDelete,
            formatBytes = formatBytes,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
    }
}

// ── Embedding model row ───────────────────────────────────────────────────────

@Composable
private fun EmbeddingModelRow(
    model: EmbeddingModelInfo,
    filePresent: Boolean,
    download: DownloadProgress?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String
) {
    val c = LocalAnvitColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        model.displayName,
                        color = c.txt0, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                    if (model.isRecommended) {
                        Text("Recommended", color = c.green, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Text(model.description, color = c.txt1, fontSize = 11.sp)
            }
            Text(
                formatBytes(model.sizeBytes),
                color = c.txt2, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        DownloadRow(
            filePresent = filePresent,
            download = download,
            sizeBytes = model.sizeBytes,
            onDownload = onDownload,
            onPause = onPause,
            onCancel = onCancel,
            onDelete = onDelete,
            formatBytes = formatBytes,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

// ── Shared download row ───────────────────────────────────────────────────────

private sealed interface DownloadUiState {
    data object Downloaded : DownloadUiState
    data class InProgress(val progress: DownloadProgress) : DownloadUiState
    data class Paused(val progress: DownloadProgress) : DownloadUiState
    data class Idle(val hasFailed: Boolean, val errorMessage: String?) : DownloadUiState
}

@Composable
private fun DownloadRow(
    filePresent: Boolean,
    download: DownloadProgress?,
    sizeBytes: Long,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier
) {
    val c = LocalAnvitColors.current
    val isDownloading  = download?.state == DownloadState.DOWNLOADING
    val isPaused       = download?.state == DownloadState.PAUSED
    val hasFailed      = download?.state == DownloadState.FAILED
    val showDownloaded = filePresent || download?.state == DownloadState.COMPLETED

    val downloadUiState: DownloadUiState = when {
        showDownloaded -> DownloadUiState.Downloaded
        isDownloading  -> DownloadUiState.InProgress(download!!)
        isPaused       -> DownloadUiState.Paused(download!!)
        else           -> DownloadUiState.Idle(hasFailed, download?.errorMessage)
    }

    AnimatedContent(
        targetState = downloadUiState,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "download_state",
        modifier = modifier.fillMaxWidth()
    ) { state ->
        when (state) {
            is DownloadUiState.Downloaded -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                        Text("Downloaded", color = SuccessGreen, fontSize = 11.sp)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Delete, "Delete model", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    }
                }
            }

            is DownloadUiState.InProgress -> {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${state.progress.progressPercent}%  ${formatBytes(state.progress.bytesDownloaded)} / ${formatBytes(state.progress.totalBytes.takeIf { it > 0 } ?: sizeBytes)}",
                                color = c.txt1, fontSize = 11.sp
                            )
                            state.progress.downloadSpeed?.let { speed ->
                                Text(speed, color = c.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row {
                            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Pause, "Pause", tint = c.accent, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Cancel, "Cancel", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.progress.progressFraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = c.accent,
                        trackColor = c.surf3
                    )
                }
            }

            is DownloadUiState.Paused -> {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Paused · ${state.progress.progressPercent}%  ${formatBytes(state.progress.bytesDownloaded)} / ${formatBytes(state.progress.totalBytes.takeIf { it > 0 } ?: sizeBytes)}",
                                color = c.txt1, fontSize = 11.sp
                            )
                        }
                        Row {
                            IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.PlayArrow, "Resume", tint = c.accent, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Cancel, "Cancel", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.progress.progressFraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = c.accent.copy(alpha = 0.5f),
                        trackColor = c.surf3
                    )
                }
            }

            is DownloadUiState.Idle -> {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.hasFailed) {
                        Text(
                            state.errorMessage ?: "Download failed. Please try again.",
                            color = ErrorRed, fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accentDim,
                            contentColor = c.accent
                        )
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.hasFailed) "Retry  (${formatBytes(sizeBytes)})"
                            else "Download  (${formatBytes(sizeBytes)})",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ── HuggingFace token section ─────────────────────────────────────────────────

@Composable
private fun HuggingFaceTokenSection(
    token: String,
    isSaved: Boolean,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val c = LocalAnvitColors.current
    var showToken by remember { mutableStateOf(false) }

    SettingsSection(title = "HuggingFace Token") {
        Text(
            "Required to download the embedding model. Get a free read token from huggingface.co after accepting the model license.",
            color = c.txt1, fontSize = 11.sp, lineHeight = 16.sp
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            placeholder = { Text("hf_...", color = c.txt2, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showToken) "Hide" else "Show",
                        tint = c.txt1, modifier = Modifier.size(18.dp)
                    )
                }
            },
            leadingIcon = {
                Icon(Icons.Default.Key, null, tint = c.accent.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.border2,
                focusedTextColor = c.txt0,
                unfocusedTextColor = c.txt0,
                cursorColor = c.accent
            ),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        )
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(36.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSaved) SuccessGreen.copy(alpha = 0.2f) else c.accentDim,
                contentColor = if (isSaved) SuccessGreen else c.accent
            )
        ) {
            if (isSaved) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Token saved", fontSize = 12.sp)
            } else {
                Text("Save Token", fontSize = 12.sp)
            }
        }
    }
}

// ── Section wrapper ───────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAnvitColors.current
    Column {
        Text(
            title.uppercase(),
            color         = c.txt2,
            fontSize      = 11.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier      = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = c.surf2),
            border = BorderStroke(0.5.dp, c.border)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

// ── Modern labeled slider ─────────────────────────────────────────────────────

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    val c = LocalAnvitColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = c.txt1, fontSize = 12.sp)
            Text(
                displayValue,
                color = c.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.surf3
            )
        )
    }
}

@Composable
private fun SwitchRow(
    title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    val c = LocalAnvitColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = c.txt0, fontSize = 14.sp)
            Text(subtitle, color = c.txt1, fontSize = 11.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (c.isDark) Color(0xFF060A0F) else Color.White,
                checkedTrackColor = c.accent,
                uncheckedTrackColor = c.surf3
            )
        )
    }
}
