package com.anvit.localai.ui.walkthrough

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvit.localai.data.models.EmbeddingModels
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.download.DownloadState
import com.anvit.localai.ui.theme.LocalAnvitColors
import com.anvit.localai.utils.formatFixed

private val TOTAL_STEPS = walkthroughStepRoutes.size

private data class StepContent(
    val title: String,
    val description: String,
)

private fun stepContent(step: Int, uiState: WalkthroughUiState): StepContent {
    return when (step) {
        0 -> StepContent(
            title = "Welcome to LocalAI",
            description = "Your 100% on-device AI assistant. Ask questions about your documents privately — nothing leaves your phone.",
        )
        1 -> {
            val rec = uiState.recommendation
            val modelName = if (rec != null)
                GemmaModels.all.find { it.id == rec.modelId }?.displayName ?: "Gemma 4 E2B"
            else "Gemma 4 E2B"
            val accelLabel = if (rec?.accelerator == "gpu") "GPU" else "CPU"
            StepContent(
                title = "Download AI Model",
                description = "Choose Gemma 4 2B or 4B. We mark $modelName as recommended for this phone ($accelLabel), and whichever you download stays on-device.",
            )
        }
        2 -> StepContent(
            title = "Download Embedding Model",
            description = "Gecko 110M powers document search. It's small (~115 MB) and lets you find relevant passages instantly.",
        )
        3 -> StepContent(
            title = "Upload Documents",
            description = "Go to the Documents tab and tap + to add PDFs. The app indexes them locally so you can ask questions.",
        )
        4 -> StepContent(
            title = "Organize with Collections",
            description = "Group related documents into Collections for focused search — like folders for your AI.",
        )
        5 -> StepContent(
            title = "You're All Set",
            description = "Pick a suggestion on the chat screen or type any question to start talking with your documents.",
        )
        else -> StepContent("", "")
    }
}

@Composable
fun WalkthroughBottomSheet(
    uiState: WalkthroughUiState,
    canAdvance: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onStartLlmDownload: (String) -> Unit,
    onSelectLlmModel: (String) -> Unit,
    onStartGeckoDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAnvitColors.current
    val step = uiState.currentStep

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = c.surf1,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .navigationBarsPadding(),
        ) {
            // Progress dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(TOTAL_STEPS) { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == step) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (i == step) c.accent else c.surf3),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Step content — animated between steps
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 }).togetherWith(
                        fadeOut(tween(160))
                    )
                },
                label = "walkthrough_step",
            ) { targetStep ->
                val content = stepContent(targetStep, uiState)
                Column {
                    Text(
                        text = content.title,
                        color = c.txt0,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = content.description,
                        color = c.txt1,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )

                    // Download card — only on steps 1 and 2
                    if (targetStep == 1 || targetStep == 2) {
                        Spacer(Modifier.height(16.dp))
                        DownloadCard(
                            step = targetStep,
                            uiState = uiState,
                            onStartLlmDownload = onStartLlmDownload,
                            onSelectLlmModel = onSelectLlmModel,
                            onStartGeckoDownload = onStartGeckoDownload,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Skip — hidden on first and last step
                if (step in 1..4) {
                    TextButton(onClick = onSkip) {
                        Text("Skip", color = c.txt2, fontSize = 14.sp)
                    }
                } else {
                    Spacer(Modifier.width(80.dp))
                }

                val isLast = step == TOTAL_STEPS - 1
                val btnLabel = if (isLast) "Get Started" else "Next"
                Button(
                    onClick = onNext,
                    enabled = canAdvance,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.accent,
                        contentColor = c.bg,
                        disabledContainerColor = c.surf3,
                        disabledContentColor = c.txt2,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(btnLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    step: Int,
    uiState: WalkthroughUiState,
    onStartLlmDownload: (String) -> Unit,
    onSelectLlmModel: (String) -> Unit,
    onStartGeckoDownload: () -> Unit,
) {
    val c = LocalAnvitColors.current
    val isLlmStep = step == 1

    if (isLlmStep) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val recommendedModelId = uiState.recommendation?.modelId
            val models = uiState.llmModelOptions.ifEmpty {
                recommendedModelId
                    ?.let { recId ->
                        GemmaModels.all.find { it.id == recId }
                            ?.let { recModel -> GemmaModels.all.filter { it.platform == recModel.platform } }
                    }
                    ?: listOf(GemmaModels.E2B, GemmaModels.E4B)
            }.sortedBy { it.sizeBytes }

            models.forEach { model ->
                ModelDownloadOption(
                    model = model,
                    isRecommended = model.id == recommendedModelId,
                    isSelected = model.id == uiState.selectedLlmModelId,
                    accelerator = uiState.recommendation?.accelerator,
                    progress = uiState.llmDownloadProgressById[model.id],
                    alreadyPresent = uiState.llmAlreadyPresentById[model.id] == true,
                    onDownload = { onStartLlmDownload(model.id) },
                    onSelect = { onSelectLlmModel(model.id) },
                )
            }
        }
        return
    }

    val progress = uiState.geckoDownloadProgress
    val alreadyPresent = uiState.geckoAlreadyPresent
    val onDownload = onStartGeckoDownload
    val modelName = EmbeddingModels.GECKO_512.displayName
    val sizeLabel = "~115 MB"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surf2)
            .border(1.dp, c.surf3, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = modelName,
                color = c.txt0,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = sizeLabel,
                color = c.txt2,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))

            when {
                alreadyPresent || progress?.state == DownloadState.COMPLETED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloaded", color = c.green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(4.dp))
                        Text("✓", color = c.green, fontSize = 13.sp)
                    }
                }
                progress?.state == DownloadState.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { progress.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = c.accent,
                        trackColor = c.surf3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${progress.progressPercent}%", color = c.txt2, fontSize = 12.sp)
                        Text(progress.downloadSpeed ?: "", color = c.txt2, fontSize = 12.sp)
                    }
                }
                progress?.state == DownloadState.FAILED -> {
                    Text(
                        text = progress.errorMessage ?: "Download failed",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Retry ($sizeLabel)", color = c.accent, fontSize = 13.sp)
                    }
                }
                else -> {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accentDim,
                            contentColor = c.accent,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Download ($sizeLabel)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadOption(
    model: GemmaModel,
    isRecommended: Boolean,
    isSelected: Boolean,
    accelerator: String?,
    progress: com.anvit.localai.download.DownloadProgress?,
    alreadyPresent: Boolean,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
) {
    val c = LocalAnvitColors.current
    val sizeLabel = "${formatFixed(model.sizeBytes / 1_073_741_824.0, 1)} GB"
    val accelLabel = if (isRecommended) {
        if (accelerator == "gpu") " · GPU recommended" else " · CPU recommended"
    } else ""

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isRecommended) c.accentDim else c.surf2)
            .border(
                width = 1.dp,
                color = if (isSelected || isRecommended) c.accent else c.surf3,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        color = c.txt0,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "$sizeLabel · ${model.ramRequired}$accelLabel",
                        color = c.txt2,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                if (isRecommended) {
                    Text(
                        text = "Recommended",
                        color = c.green,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                alreadyPresent || progress?.state == DownloadState.COMPLETED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloaded", color = c.green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        if (isSelected) {
                            Text("Selected", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        } else {
                            TextButton(
                                onClick = onSelect,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Use this model", color = c.accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
                progress?.state == DownloadState.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { progress.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = c.accent,
                        trackColor = c.surf3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${progress.progressPercent}%", color = c.txt2, fontSize = 12.sp)
                        Text(progress.downloadSpeed ?: "", color = c.txt2, fontSize = 12.sp)
                    }
                }
                progress?.state == DownloadState.FAILED -> {
                    Text(
                        text = progress.errorMessage ?: "Download failed",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Retry ($sizeLabel)", color = c.accent, fontSize = 13.sp)
                    }
                }
                else -> {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecommended) c.accent else c.accentDim,
                            contentColor = if (isRecommended) c.bg else c.accent,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Download ($sizeLabel)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
