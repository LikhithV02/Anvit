package com.sage.localai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sage.localai.agentic.AgentStep
import com.sage.localai.ui.theme.*

val stepIcons = mapOf(
    "route" to "[route]",
    "decompose" to "[decomp]",
    "retrieve" to "[fetch]",
    "requery" to "[retry]",
    "supplement" to "[extra]",
    "reduce" to "[trim]",
    "generate" to "[gen]"
)

@Composable
fun AgentStepsPanel(steps: List<AgentStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AccountTree, "Agent steps", tint = AgentStepBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Agentic reasoning (${steps.size} steps)", color = AgentStepBlue, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = AgentStepBlue, modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                steps.forEach { step ->
                    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text(stepIcons[step.type] ?: "•", fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp, top = 1.dp))
                        Text(step.description, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Default)
                    }
                }
            }
        }
    }
}
