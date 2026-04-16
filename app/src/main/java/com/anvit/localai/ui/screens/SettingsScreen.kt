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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anvit.localai.data.models.EmbeddingModelInfo
import com.anvit.localai.data.models.EmbeddingModels
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.download.DownloadProgress
import com.anvit.localai.download.DownloadState
import com.anvit.localai.ui.theme.*
import com.anvit.localai.ui.viewmodels.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Settings", color = TealPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        // ── Gemma Model Selection ──────────────────────────────────────────────
        SettingsSection(title = "Gemma Model") {
            GemmaModels.all.forEachIndexed { index, model ->
                val selected = uiState.selectedModelId == model.id
                val download = uiState.downloads[model.id]
                val filePresent = viewModel.isModelFilePresent(model.fileName)

                GemmaModelRow(
                    model = model,
                    selected = selected,
                    filePresent = filePresent,
                    download = download,
                    onSelect = { viewModel.selectModel(model.id) },
                    onDownload = { viewModel.downloadGemmaModel(model) },
                    onCancel = { viewModel.cancelDownload(model.id) },
                    onDelete = { viewModel.deleteModel(model.fileName, model.id) },
                    formatBytes = viewModel::formatBytes
                )
                if (index < GemmaModels.all.lastIndex) {
                    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                }
            }

            Spacer(Modifier.height(4.dp))

            val selectedModelFilePresent = GemmaModels.all
                .find { it.id == uiState.selectedModelId }
                ?.let { viewModel.isModelFilePresent(it.fileName) } ?: false

            Button(
                onClick = { viewModel.loadSelectedModel() },
                enabled = !uiState.isLoadingModel && selectedModelFilePresent,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TealPrimary,
                    contentColor = Surface0,
                    disabledContainerColor = TealPrimary.copy(alpha = 0.3f),
                    disabledContentColor = Surface0.copy(alpha = 0.5f)
                )
            ) {
                if (uiState.isLoadingModel) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Surface0, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading model...")
                } else {
                    Text(
                        if (selectedModelFilePresent) "Load Selected Model" else "Download model first",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            uiState.modelLoadSuccess?.let { msg ->
                Text(msg, color = SuccessGreen, fontSize = 12.sp)
                LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); viewModel.clearMessages() }
            }
            uiState.modelLoadError?.let { msg ->
                Text(msg, color = ErrorRed, fontSize = 12.sp)
            }
        }

        // ── Embedding Model ────────────────────────────────────────────────────
        SettingsSection(title = "Embedding Model") {
            EmbeddingModels.all.forEachIndexed { index, model ->
                val download = uiState.downloads[model.id]
                val filePresent = viewModel.isModelFilePresent(model.fileName)

                EmbeddingModelRow(
                    model = model,
                    filePresent = filePresent,
                    download = download,
                    onDownload = { viewModel.downloadEmbeddingModel(model) },
                    onCancel = { viewModel.cancelDownload(model.id) },
                    onDelete = { viewModel.deleteModel(model.fileName, model.id) },
                    formatBytes = viewModel::formatBytes
                )
                if (index < EmbeddingModels.all.lastIndex) {
                    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                }
            }

            HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Status", color = TextSecondary, fontSize = 12.sp)
                    Text(uiState.embeddingModelStatus, color = TextPrimary, fontSize = 13.sp)
                }
                Button(
                    onClick = { viewModel.initializeEmbedding() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TealPrimary.copy(alpha = 0.2f),
                        contentColor = TealPrimary
                    )
                ) {
                    Text("Initialize", fontSize = 12.sp)
                }
            }
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
            Text("Retrieval Mode", color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("vector", "bm25", "hybrid").forEach { mode ->
                    FilterChip(
                        selected = uiState.retrievalMode == mode,
                        onClick = { viewModel.setRetrievalMode(mode) },
                        label = { Text(mode.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TealPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = TealPrimary,
                            labelColor = TextSecondary
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
            // Accelerator selection
            Text("Accelerator", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("cpu", "gpu").forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = uiState.accelerator == option,
                        onClick = { viewModel.setAccelerator(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = TealPrimary.copy(alpha = 0.2f),
                            activeContentColor = TealPrimary,
                            inactiveContentColor = TextSecondary
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
                    color = androidx.compose.ui.graphics.Color(0xFFFFA726),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            LabeledSlider(
                label = "Temperature",
                value = uiState.temperature,
                displayValue = "%.2f".format(uiState.temperature),
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

            LabeledSlider(
                label = "Max Output Tokens",
                value = uiState.maxOutputTokens.toFloat(),
                displayValue = "${uiState.maxOutputTokens}",
                onValueChange = { viewModel.setMaxOutputTokens(it.toInt()) },
                valueRange = 100f..32000f,
                steps = 31
            )

            HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
            TextButton(
                onClick = { viewModel.resetGenerationDefaults() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
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
                Text("Anvit Local AI", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Surface(shape = RoundedCornerShape(6.dp), color = TealPrimary.copy(alpha = 0.15f)) {
                    Text(
                        "v1.0.0",
                        color = TealLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                "On-device AI powered by Gemma with Retrieval-Augmented Generation. All processing happens locally — your data never leaves your device.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Gemma model row ───────────────────────────────────────────────────────────

@Composable
private fun GemmaModelRow(
    model: GemmaModel,
    selected: Boolean,
    filePresent: Boolean,
    download: DownloadProgress?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String
) {
    val rowModifier = if (selected) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TealPrimary.copy(alpha = 0.06f))
            .border(1.5.dp, TealPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(6.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier = rowModifier) {
        // Line 1: radio + name + size chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected, onClick = onSelect,
                modifier = Modifier.size(24.dp),
                colors = RadioButtonDefaults.colors(selectedColor = TealPrimary, unselectedColor = TextSecondary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                model.displayName,
                modifier = Modifier.weight(1f),
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(6.dp), color = TealPrimary.copy(alpha = 0.2f)) {
                Text(
                    model.sizeLabel, color = TealPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        // Line 2: RAM info (indented under name)
        Text(
            model.ramRequired,
            modifier = Modifier.padding(start = 40.dp),
            color = TextSecondary, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        // Line 3: download status (indented)
        DownloadRow(
            filePresent = filePresent,
            download = download,
            sizeBytes = model.sizeBytes,
            onDownload = onDownload,
            onCancel = onCancel,
            onDelete = onDelete,
            formatBytes = formatBytes,
            modifier = Modifier.padding(start = 40.dp)
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
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Line 1: name + optional badge + size chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                model.displayName,
                modifier = Modifier.weight(1f),
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            if (model.isRecommended) {
                Surface(shape = RoundedCornerShape(4.dp), color = SuccessGreen.copy(alpha = 0.2f)) {
                    Text(
                        "Rec.", color = SuccessGreen, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Surface(shape = RoundedCornerShape(6.dp), color = TealPrimary.copy(alpha = 0.15f)) {
                Text(
                    formatBytes(model.sizeBytes), color = TealLight, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        // Line 2: description
        Text(model.description, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        // Line 3: download status
        DownloadRow(
            filePresent = filePresent,
            download = download,
            sizeBytes = model.sizeBytes,
            onDownload = onDownload,
            onCancel = onCancel,
            onDelete = onDelete,
            formatBytes = formatBytes
        )
    }
}

// ── Shared download row ───────────────────────────────────────────────────────

/** Three-state sealed class used as AnimatedContent target to smooth transitions. */
private sealed interface DownloadUiState {
    data object Downloaded : DownloadUiState
    data class InProgress(val progress: DownloadProgress) : DownloadUiState
    data class Idle(val hasFailed: Boolean, val errorMessage: String?) : DownloadUiState
}

@Composable
private fun DownloadRow(
    filePresent: Boolean,
    download: DownloadProgress?,
    sizeBytes: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier
) {
    val isDownloading = download?.state == DownloadState.DOWNLOADING
    val hasFailed     = download?.state == DownloadState.FAILED
    val showDownloaded = filePresent || download?.state == DownloadState.COMPLETED

    // Map to our sealed target so AnimatedContent can distinguish each state
    val downloadUiState: DownloadUiState = when {
        showDownloaded -> DownloadUiState.Downloaded
        isDownloading  -> DownloadUiState.InProgress(download!!)
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
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, "Delete model", tint = ErrorRed.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
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
                        Text(
                            "${state.progress.progressPercent}%  ${formatBytes(state.progress.bytesDownloaded)} / ${formatBytes(state.progress.totalBytes.takeIf { it > 0 } ?: sizeBytes)}",
                            color = TextSecondary, fontSize = 11.sp
                        )
                        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Cancel, "Cancel", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.progress.progressFraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = TealPrimary,
                        trackColor = Surface2
                    )
                }
            }

            is DownloadUiState.Idle -> {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.hasFailed) {
                        Text(
                            "Failed: ${state.errorMessage ?: "Unknown error"}",
                            color = ErrorRed, fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TealPrimary.copy(alpha = if (state.hasFailed) 0.15f else 0.2f),
                            contentColor = TealPrimary
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
    var showToken by remember { mutableStateOf(false) }

    SettingsSection(title = "HuggingFace Token") {
        Text(
            "Required for gated models (EmbeddingGemma, Gecko). " +
            "Get a read token at huggingface.co/settings/tokens after accepting the model license.",
            color = TextSecondary, fontSize = 11.sp
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("hf_...", color = TextHint, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showToken) "Hide" else "Show",
                        tint = TextSecondary, modifier = Modifier.size(18.dp)
                    )
                }
            },
            leadingIcon = {
                Icon(Icons.Default.Key, null, tint = TealPrimary.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TealPrimary,
                unfocusedBorderColor = BorderDefault,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = TealPrimary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        )
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(36.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSaved) SuccessGreen.copy(alpha = 0.2f) else TealPrimary.copy(alpha = 0.2f),
                contentColor = if (isSaved) SuccessGreen else TealPrimary
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
    Column {
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = TealPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Box(modifier = Modifier.weight(1f).height(1.dp).background(
                Brush.horizontalGradient(listOf(TealPrimary.copy(0.4f), TealPrimary.copy(0f)))
            ))
        }
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Surface2),
            border = BorderStroke(0.5.dp, BorderSubtle)
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = TealPrimary.copy(alpha = 0.12f)
            ) {
                Text(
                    displayValue,
                    color = TealPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = TealPrimary,
                activeTrackColor = TealPrimary,
                inactiveTrackColor = Surface3
            )
        )
    }
}

@Composable
private fun SwitchRow(
    title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Surface0, checkedTrackColor = TealPrimary, uncheckedTrackColor = Surface3
            )
        )
    }
}
