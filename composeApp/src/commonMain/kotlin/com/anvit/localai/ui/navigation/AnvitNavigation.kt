package com.anvit.localai.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.anvit.localai.ui.screens.AboutScreen
import com.anvit.localai.ui.screens.ChatScreen
import com.anvit.localai.ui.screens.DocumentsScreen
import com.anvit.localai.ui.screens.PrivacyPolicyScreen
import com.anvit.localai.ui.screens.SettingsScreen
import com.anvit.localai.ui.theme.LocalAnvitColors
import com.anvit.localai.ui.walkthrough.WalkthroughBottomSheet
import com.anvit.localai.ui.walkthrough.WalkthroughViewModel
import com.anvit.localai.ui.walkthrough.walkthroughStepRoutes
import org.koin.compose.viewmodel.koinViewModel

sealed class SageScreen(val route: String, val label: String, val icon: ImageVector) {
    object Chat      : SageScreen("chat",      "Chat",      Icons.Default.Chat)
    object Documents : SageScreen("documents", "Documents", Icons.Default.Description)
    object Settings  : SageScreen("settings",  "Settings",  Icons.Default.Settings)
}

val bottomNavItems = listOf(SageScreen.Chat, SageScreen.Documents, SageScreen.Settings)

private val screenOrder = listOf(SageScreen.Chat.route, SageScreen.Documents.route, SageScreen.Settings.route)

private const val TRANSITION_DURATION = 280

@Composable
fun AnvitNavHost(onRequestAppReview: () -> Unit = {}) {
    val c = LocalAnvitColors.current
    val navController = rememberNavController()

    val walkthroughVm: WalkthroughViewModel = koinViewModel()
    val walkthroughState by walkthroughVm.uiState.collectAsState()

    // Drive tab navigation to match the current walkthrough step
    LaunchedEffect(walkthroughState.currentStep) {
        if (!walkthroughState.showSheet) return@LaunchedEffect
        val targetRoute = walkthroughStepRoutes[walkthroughState.currentStep]
        if (navController.currentDestination?.route != targetRoute) {
            navController.navigate(targetRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier       = Modifier.imePadding(),
            containerColor = c.bg,
            bottomBar      = { FloatingNavBar(navController) },
        ) { innerPadding ->
            NavHost(
                navController    = navController,
                startDestination = SageScreen.Chat.route,
                modifier         = Modifier.padding(innerPadding),
                enterTransition  = {
                    val fromIndex = screenOrder.indexOf(initialState.destination.route)
                    val toIndex   = screenOrder.indexOf(targetState.destination.route)
                    val dir = if (toIndex > fromIndex)
                        AnimatedContentTransitionScope.SlideDirection.Start
                    else
                        AnimatedContentTransitionScope.SlideDirection.End
                    slideIntoContainer(dir, tween(TRANSITION_DURATION)) + fadeIn(tween(TRANSITION_DURATION))
                },
                exitTransition = {
                    val fromIndex = screenOrder.indexOf(initialState.destination.route)
                    val toIndex   = screenOrder.indexOf(targetState.destination.route)
                    val dir = if (toIndex > fromIndex)
                        AnimatedContentTransitionScope.SlideDirection.Start
                    else
                        AnimatedContentTransitionScope.SlideDirection.End
                    slideOutOfContainer(dir, tween(TRANSITION_DURATION)) + fadeOut(tween(TRANSITION_DURATION))
                },
                popEnterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_DURATION)) +
                        fadeIn(tween(TRANSITION_DURATION))
                },
                popExitTransition = {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_DURATION)) +
                        fadeOut(tween(TRANSITION_DURATION))
                },
            ) {
                composable(SageScreen.Chat.route)      { ChatScreen(onRequestAppReview = onRequestAppReview) }
                composable(SageScreen.Documents.route) { DocumentsScreen() }
                composable(SageScreen.Settings.route)  { SettingsScreen(navController) }
                composable("about")   { AboutScreen(onBack = { navController.popBackStack() }) }
                composable("privacy") { PrivacyPolicyScreen(onBack = { navController.popBackStack() }) }
            }
        }

        if (walkthroughState.showSheet) {
            WalkthroughBottomSheet(
                uiState = walkthroughState,
                canAdvance = walkthroughVm.canAdvance(walkthroughState.currentStep),
                onNext = walkthroughVm::nextStep,
                onSkip = walkthroughVm::skip,
                onStartLlmDownload = walkthroughVm::startLlmDownload,
                onSelectLlmModel = walkthroughVm::selectLlmModel,
                onStartGeckoDownload = walkthroughVm::startGeckoDownload,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun FloatingNavBar(navController: NavController) {
    val c = LocalAnvitColors.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(36.dp))
                .background(c.surf2)
                .border(1.dp, c.border2, RoundedCornerShape(36.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomNavItems.forEach { screen ->
                val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                FloatingNavItem(
                    screen   = screen,
                    selected = selected,
                    onClick  = {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FloatingNavItem(screen: SageScreen, selected: Boolean, onClick: () -> Unit) {
    val c = LocalAnvitColors.current
    val bgColor by animateColorAsState(
        targetValue   = if (selected) c.accentDim else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(240),
        label         = "nav_pill_bg",
    )
    val iconTint by animateColorAsState(
        targetValue   = if (selected) c.accent else c.txt2,
        animationSpec = tween(240),
        label         = "nav_icon_tint",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector        = screen.icon,
                contentDescription = screen.label,
                tint               = iconTint,
                modifier           = Modifier.size(20.dp),
            )
            AnimatedVisibility(
                visible = selected,
                enter   = fadeIn(tween(200)) + expandHorizontally(tween(260), Alignment.Start),
                exit    = fadeOut(tween(140)) + shrinkHorizontally(tween(200), Alignment.Start),
            ) {
                Text(
                    text       = screen.label,
                    color      = c.accent,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                )
            }
        }
    }
}
