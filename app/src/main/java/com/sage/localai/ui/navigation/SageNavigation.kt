package com.sage.localai.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.sage.localai.ui.screens.ChatScreen
import com.sage.localai.ui.screens.DocumentsScreen
import com.sage.localai.ui.screens.SettingsScreen
import com.sage.localai.ui.theme.*

sealed class SageScreen(val route: String, val label: String, val icon: ImageVector) {
    object Chat      : SageScreen("chat",      "Chat",      Icons.Default.Chat)
    object Documents : SageScreen("documents", "Documents", Icons.Default.Description)
    object Settings  : SageScreen("settings",  "Settings",  Icons.Default.Settings)
}

val bottomNavItems = listOf(SageScreen.Chat, SageScreen.Documents, SageScreen.Settings)

private val screenOrder = listOf(SageScreen.Chat.route, SageScreen.Documents.route, SageScreen.Settings.route)

private const val TRANSITION_DURATION = 280

@Composable
fun SageNavHost() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Surface0,
        bottomBar = {
            Column {
                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                NavigationBar(containerColor = Surface1, tonalElevation = 8.dp) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (selected) {
                                    // Pill-shaped container for selected icon — Material3 Expressive pattern
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = TealPrimary.copy(alpha = 0.15f)
                                    ) {
                                        Icon(
                                            screen.icon, screen.label,
                                            tint = TealPrimary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    Icon(screen.icon, screen.label)
                                }
                            },
                            label = { Text(screen.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = TealPrimary,
                                selectedTextColor   = TealPrimary,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor      = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    }
                }
            }
        }
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
