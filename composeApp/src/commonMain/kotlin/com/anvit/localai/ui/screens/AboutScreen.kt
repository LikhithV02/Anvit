package com.anvit.localai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
fun AboutScreen(onBack: () -> Unit) {
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
            Text("About Anvit", color = c.txt0, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // Hero card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surf1)
                    .border(1.dp, c.border, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("🧠", fontSize = 48.sp)
                Text("Anvit", color = c.txt0, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Local Agentic RAG", color = c.txt1, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(c.greenDim)
                        .border(1.dp, c.green.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("100% On-Device AI", color = c.green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Description
            InfoCard(title = "What is Anvit?") {
                Text(
                    "Anvit is a private, on-device AI assistant that helps you chat with PDFs, uncover key insights, compare documents, and get clear answers grounded in your own files.",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Built with advanced agentic RAG, Anvit goes beyond simple document search. It breaks complex questions into smaller steps, retrieves the most relevant passages, refines weak results, and generates sharper answers from your document library.",
                    color = c.txt1, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No cloud. No tracking. No data collection. Your documents, questions, and conversations never leave your phone.",
                    color = c.txt0, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                )
            }

            // Key features
            InfoCard(title = "Key Features") {
                val features = listOf(
                    "100% On-Device AI" to "Gemma 4 (2B or 4B) runs locally. Once models are downloaded, the app works fully offline.",
                    "Agentic RAG Pipeline" to "Complex questions are automatically broken into focused sub-queries, retrieved across multiple passes, and refined.",
                    "Hybrid Retrieval" to "Combines dense semantic search with BM25 keyword search. Finds what you mean, not just what you type.",
                    "Corrective RAG (CRAG)" to "When retrieval quality is poor, the app automatically re-queries with rephrased terms instead of hallucinating.",
                    "Multiple Collections" to "Organize your PDFs into named collections and query each independently.",
                    "Cited Sources" to "Every answer includes the source document and the exact passages used.",
                )
                features.forEachIndexed { i, (title, desc) ->
                    if (i > 0) HorizontalDivider(color = c.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = c.accent, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                        Column {
                            Text(title, color = c.txt0, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(desc, color = c.txt1, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }

            // Great for
            InfoCard(title = "Great For") {
                val useCases = listOf(
                    "Students studying textbooks and research papers",
                    "Professionals reviewing contracts, reports, and specifications",
                    "Researchers querying large PDF archives",
                    "Anyone who values privacy over convenience",
                )
                useCases.forEach { useCase ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("·", color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(useCase, color = c.txt1, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }

            // Version footer
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text("v1.0.0  ·  © 2026 Anvit", color = c.txt2, fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAnvitColors.current
    Column {
        Text(
            title.uppercase(),
            color = c.txt2, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surf1)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}
