package com.anvit.localai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvit.localai.agentic.AgentStep
import com.anvit.localai.ui.theme.*

private data class StepMeta(val icon: ImageVector, val label: String, val color: Color)

private fun stepMeta(type: String): StepMeta = when (type) {
    "route"      -> StepMeta(Icons.Outlined.AutoAwesome, "Planning",     StepPlanning)
    "decompose"  -> StepMeta(Icons.Default.AccountTree,  "Breaking down", StepBreaking)
    "retrieve"   -> StepMeta(Icons.Default.Search,       "Searching",    StepSearching)
    "requery"    -> StepMeta(Icons.Default.Refresh,      "Retrying",     StepBreaking)
    "supplement" -> StepMeta(Icons.Default.Add,          "Supplementing", StepSupplementing)
    "reduce"     -> StepMeta(Icons.Default.FilterList,   "Filtering",    StepFiltering)
    "generate"   -> StepMeta(Icons.Default.Edit,         "Generating",   StepGenerating)
    else         -> StepMeta(Icons.Default.Info,         "Processing",   StepSupplementing)
}

@Composable
fun AgentStepsPanel(steps: List<AgentStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val c = LocalAnvitColors.current
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(c.surf1)
                .border(1.dp, c.border, RoundedCornerShape(100.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "Agentic Reasoning",
                color      = c.txt0,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(c.accentDim)
                    .border(1.dp, c.border2, RoundedCornerShape(100.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    "${steps.size} steps",
                    color      = c.accent,
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint     = c.txt2,
                modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
                steps.forEachIndexed { index, step ->
                    AgentStepRow(step, c)
                    if (index < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .padding(start = 11.dp)
                                .width(1.dp)
                                .height(8.dp)
                                .background(c.border2),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStepRow(step: AgentStep, c: AnvitColors) {
    val meta = stepMeta(step.type)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(meta.color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(meta.icon, null, tint = meta.color, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(meta.color.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    meta.label,
                    color      = meta.color,
                    fontSize   = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(step.description, color = c.txt1, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
