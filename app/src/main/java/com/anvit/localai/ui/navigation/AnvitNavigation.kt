package com.anvit.localai.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
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
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.anvit.localai.ui.screens.ChatScreen
import com.anvit.localai.ui.screens.DocumentsScreen
import com.anvit.localai.ui.screens.SettingsScreen
import com.anvit.localai.ui.theme.*

sealed class SageScreen(val route: String, val label: String, val icon: ImageVector) {
    object Chat      : SageScreen("chat",      "Chat",      Icons.Default.Chat)
    object Documents : SageScreen("documents", "Documents", Icons.Default.Description)
    object Settings  : SageScreen("settings",  "Settings",  Icons.Default.Settings)
}

val bottomNavItems = listOf(SageScreen.Chat, SageScreen.Documents, SageScreen.Settings)

private val screenOrder = listOf(SageScreen.Chat.route, SageScreen.Documents.route, SageScreen.Settings.route)

private const val TRANSITION_DURATION = 280

@Composable
fun AnvitNavHost() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Surface0,
        bottomBar = { FloatingNavBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = SageScreen.Chat.route,
            modifier         = Modifier.padding(innerPadding),
            enterTransition  = {
                val fromIndex = screenOrder.indexOf(initialState.destination.route)
                val toIndex   = screenOrder.indexOf(targetState.destination.route)
                val direction = if (toIndex > fromIndex)
                    AnimatedContentTransitionScope.SlideDirection.Start
                else
                    AnimatedContentTransitionScope.SlideDirection.End
                slideIntoContainer(direction, tween(TRANSITION_DURATION)) +
                    fadeIn(tween(TRANSITION_DURATION))
            },
            exitTransition = {
                val fromIndex = screenOrder.indexOf(initialState.destination.route)
                val toIndex   = screenOrder.indexOf(targetState.destination.route)
                val direction = if (toIndex > fromIndex)
                    AnimatedContentTransitionScope.SlideDirection.Start
                else
                    AnimatedContentTransitionScope.SlideDirection.End
                slideOutOfContainer(direction, tween(TRANSITION_DURATION)) +
                    fadeOut(tween(TRANSITION_DURATION))
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(TRANSITION_DURATION)
                ) + fadeIn(tween(TRANSITION_DURATION))
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(TRANSITION_DURATION)
                ) + fadeOut(tween(TRANSITION_DURATION))
            }
        ) {
            composable(SageScreen.Chat.route)      { ChatScreen() }
            composable(SageScreen.Documents.route) { DocumentsScreen() }
            composable(SageScreen.Settings.route)  { SettingsScreen() }
        }
    }
}

// ── Floating pill nav bar ─────────────────────────────────────────────────────

@Composable
private fun FloatingNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = Surface2,
            shadowElevation = 24.dp,
            border = BorderStroke(0.5.dp, BorderSubtle)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == screen.route } == true
                    FloatingNavItem(
                        screen   = screen,
                        selected = selected,
                        onClick  = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    screen: SageScreen,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue  = if (selected) TealPrimary.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(240),
        label        = "nav_pill_bg"
    )
    val iconTint by animateColorAsState(
        targetValue  = if (selected) TealPrimary else TextSecondary,
        animationSpec = tween(240),
        label        = "nav_icon_tint"
    )

    // Pill grows horizontally to accommodate the label when selected
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector        = screen.icon,
                contentDescription = screen.label,
                tint               = iconTint,
                modifier           = Modifier.size(20.dp)
            )
            // Label slides in from the right when this tab is selected
            AnimatedVisibility(
                visible = selected,
                enter   = fadeIn(tween(200)) + expandHorizontally(
                    animationSpec = tween(260),
                    expandFrom    = Alignment.Start
                ),
                exit    = fadeOut(tween(140)) + shrinkHorizontally(
                    animationSpec = tween(200),
                    shrinkTowards = Alignment.Start
                )
            ) {
                Text(
                    text       = screen.label,
                    color      = TealPrimary,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1
                )
            }
        }
    }
}
