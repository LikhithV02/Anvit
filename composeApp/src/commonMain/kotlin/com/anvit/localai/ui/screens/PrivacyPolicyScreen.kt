package com.anvit.localai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvit.localai.ui.theme.LocalAnvitColors

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val c = LocalAnvitColors.current

    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg)
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = c.txt0)
            }
            Text("Privacy Policy", color = c.txt0, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surf1)
                    .border(1.dp, c.border, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("🔒", fontSize = 36.sp)
                Text("Privacy Policy", color = c.txt0, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Anvit: Local Agentic RAG", color = c.txt1, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(c.greenDim)
                        .border(1.dp, c.green.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("100% On-Device AI", color = c.green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Effective: April 23, 2026", color = c.txt2, fontSize = 11.sp)
            }

            // Overview
            PolicyCard(emoji = "🔒", title = "Overview") {
                Text(
                    "Anvit is a privacy-first AI assistant that runs entirely on your device. It lets you chat with your documents using a local Gemma 4 language model — no cloud inference, no analytics, no tracking.",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "This policy explains what data Anvit stores locally, what limited information may leave your device, and how you remain in control at all times.",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
            }

            // Data stored on device
            PolicyCard(emoji = "📱", title = "Data Stored on Your Device") {
                Text(
                    "All of the following is stored only on your device and is never transmitted to any server:",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(8.dp))
                val items = listOf(
                    "Chat history & messages" to "Persist conversations across sessions",
                    "Uploaded PDF documents" to "Source material for document Q&A",
                    "Text embeddings (vectors)" to "Enable local semantic search (RAG)",
                    "AI model files (LLM & embedding)" to "Run inference fully offline after download",
                    "App settings & preferences" to "Remember your configuration",
                    "HuggingFace token (optional)" to "Authenticate model downloads if provided",
                    "Email address (optional)" to "Pre-fill the feedback form if provided",
                )
                items.forEach { (data, purpose) ->
                    HorizontalDivider(color = c.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 3.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(data, color = c.txt0, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(purpose, color = c.txt2, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Audio
            PolicyCard(emoji = "🎙️", title = "Audio Data & Microphone") {
                Text(
                    "Anvit requests microphone permission to enable voice-to-text interactions with your local AI agent.",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(8.dp))
                HighlightBox {
                    PolicyBullet("On-Device Processing: All audio is processed strictly on your device using local speech-to-text engines.")
                    PolicyBullet("No Cloud Transmission: Audio data is never uploaded to any external servers or cloud providers.")
                    PolicyBullet("No Retention: Raw audio files are deleted immediately after the local transcription process completes.")
                }
            }

            // Network activity
            PolicyCard(emoji = "🌐", title = "Network Activity") {
                Text(
                    "Anvit makes network requests in two narrow, user-initiated situations:",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text("1. Model Downloads — HuggingFace", color = c.txt0, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "When you download a model in Settings, Anvit fetches files from huggingface.co. No personal data is sent. Your HuggingFace token is used only as an authorization header and is stored locally.",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text("2. Voluntary Feedback — Google Forms", color = c.txt0, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("If you submit a feedback form, the following is sent:", color = c.txt1, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                listOf(
                    "A hashed message ID (8-character hex, not linked to your identity)",
                    "The query and AI response you found inaccurate",
                    "Your selected feedback reason",
                    "Your email address — only if you typed it in",
                ).forEach { PolicyBullet(it) }
                Spacer(Modifier.height(8.dp))
                HighlightBox {
                    Text(
                        "No other network calls are made. All AI inference, document retrieval, and embedding computation runs locally. Anvit has no telemetry, crash reporting, or background analytics.",
                        color = c.txt1, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
            }

            // Never does
            PolicyCard(emoji = "🚫", title = "What Anvit Never Does") {
                val nevers = listOf(
                    "Sends your documents or queries to cloud AI services",
                    "Uploads audio or transcriptions to external servers",
                    "Collects analytics, behavioral data, or usage telemetry",
                    "Displays advertisements or shares data with ad networks",
                    "Sells or monetizes your personal data",
                    "Accesses your files beyond what you explicitly select",
                )
                nevers.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(c.pinkDim)
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text("Never", color = c.pink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(item, color = c.txt1, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }

            // Permissions
            PolicyCard(emoji = "🛡️", title = "Permissions Explained") {
                val perms = listOf(
                    "INTERNET" to "One-time model downloads from HuggingFace; optional feedback submission. Not used for inference.",
                    "RECORD_AUDIO" to "Voice-to-text queries. Audio never leaves your device.",
                    "File / Storage" to "Selecting PDF files via the system file picker (scoped storage — only files you choose).",
                )
                perms.forEachIndexed { i, (perm, reason) ->
                    if (i > 0) HorizontalDivider(color = c.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                    Text(perm, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text(reason, color = c.txt1, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }

            // Data deletion
            PolicyCard(emoji = "🗑️", title = "Data Deletion") {
                Text(
                    "Because all data is stored locally, you can delete it at any time:",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(6.dp))
                PolicyBullet("Chat history: Delete individual sessions or clear all history from within the app.")
                PolicyBullet("Documents: Remove documents from any collection inside the app.")
                PolicyBullet("All app data: Uninstall Anvit to permanently erase all stored data, models, and preferences.")
            }

            // Contact
            PolicyCard(emoji = "✉️", title = "Contact") {
                Text(
                    "Questions or concerns about this policy? Reach out at:",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("likhithv02@gmail.com", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            // Footer
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("© 2026 Anvit. All AI processing is local. Your data stays yours.", color = c.txt2, fontSize = 11.sp)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PolicyCard(emoji: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAnvitColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
            Text(emoji, fontSize = 13.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                title.uppercase(),
                color = c.txt2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surf1)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun HighlightBox(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAnvitColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.accentDim)
            .border(1.dp, c.border2, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun PolicyBullet(text: String) {
    val c = LocalAnvitColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        Text("·", color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(text, color = c.txt1, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
