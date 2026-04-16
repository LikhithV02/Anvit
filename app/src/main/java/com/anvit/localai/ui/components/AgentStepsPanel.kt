package com.anvit.localai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvit.localai.agentic.AgentStep
import com.anvit.localai.ui.theme.*

private data class StepMeta(val icon: ImageVector, val label: String, val color: Color)

private fun stepMeta(type: String): StepMeta = when (type) {
    "route"      -> StepMeta(Icons.Outlined.AutoAwesome, "Planning",    AgentStepBlue)
    "decompose"  -> StepMeta(Icons.Default.AccountTree,  "Breaking down", WarningAmber)
    "retrieve"   -> StepMeta(Icons.Default.Search,       "Searching",   AgentStepBlue)
    "requery"    -> StepMeta(Icons.Default.Refresh,      "Retrying",    WarningAmber)
    "supplement" -> StepMeta(Icons.Default.Add,          "Adding more", SuccessGreen)
    "reduce"     -> StepMeta(Icons.Default.FilterList,   "Filtering",   TealDark)
    "generate"   -> StepMeta(Icons.Default.Edit,         "Answering",   SuccessGreen)
    else         -> StepMeta(Icons.Default.Info,         "Processing",  TextSecondary)
}

@Composable
fun AgentStepsPanel(steps: List<AgentStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AutoAwesome, null,
                tint = AgentStepBlue,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Agentic Reasoning",
                color = AgentStepBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AgentStepBlue.copy(alpha = 0.15f)
            ) {
                Text(
                    "${steps.size} steps",
                    color = AgentStepBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint = AgentStepBlue.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp)
            )
        }

        // ── Expandable step list ──────────────────────────────────────────────
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                steps.forEachIndexed { index, step ->
                    AgentStepRow(step)
                    if (index < steps.lastIndex) {
                        // Connector line between steps
                        Box(
                            modifier = Modifier
                                .padding(start = 11.dp)
                                .width(2.dp)
                                .height(6.dp)
                                .background(BorderDefault)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStepRow(step: AgentStep) {
    val meta = stepMeta(step.type)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // Colored icon in a small circle
        Surface(
            shape = CircleShape,
            color = meta.color.copy(alpha = 0.12f),
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                meta.icon, null,
                tint = meta.color,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
            // Step type label pill
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = meta.color.copy(alpha = 0.10f)
            ) {
                Text(
                    meta.label,
                    color = meta.color,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                step.description,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
