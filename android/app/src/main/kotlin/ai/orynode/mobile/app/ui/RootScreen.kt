package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.ui.preview.DocumentPreviewScreen
import ai.orynode.mobile.app.ui.theme.OnboardingStageLayout
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.app.ui.theme.PaperBackground
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun RootScreen(
    appViewModel: AppViewModel,
    knowledgeViewModel: KnowledgeBaseViewModel,
) {
    val appState by appViewModel.state.collectAsStateWithLifecycle()
    val kbState by knowledgeViewModel.state.collectAsStateWithLifecycle()

    // Window contract: paper surface so SAF resume never shows a black buffer.
    Surface(modifier = Modifier.fillMaxSize(), color = OrynodeColors.paper) {
        when (appState.phase) {
            AppPhase.Launching -> LaunchScreen(appViewModel)
            AppPhase.NeedsModel -> ModelSetupScreen(appViewModel)
            AppPhase.Ready -> {
                val navController = rememberNavController()
                LaunchedEffect(kbState.previewIntent) {
                    if (kbState.previewIntent != null &&
                        navController.currentBackStackEntry?.destination?.route != "preview"
                    ) {
                        // Push on top of home or chat — do not clear the stack.
                        navController.navigate("preview") {
                            launchSingleTop = true
                        }
                    }
                }
                PaperBackground {
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable("home") {
                            HomeScreen(
                                appViewModel = appViewModel,
                                knowledgeViewModel = knowledgeViewModel,
                                onOpenChat = { navController.navigate("chat") },
                            )
                        }
                        composable("chat") {
                            KnowledgeChatScreen(
                                viewModel = knowledgeViewModel,
                                state = kbState,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("preview") {
                            // Leaving preview (toolbar or system back) always restores prior screen.
                            DisposableEffect(Unit) {
                                onDispose { knowledgeViewModel.clearPreview() }
                            }
                            val intent = kbState.previewIntent
                            if (intent == null) {
                                LaunchedEffect(Unit) {
                                    navController.popBackStack()
                                }
                            } else {
                                DocumentPreviewScreen(
                                    intent = intent,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
                if (appState.showsSettings) {
                    SettingsScreen(
                        appViewModel = appViewModel,
                        knowledgeViewModel = knowledgeViewModel,
                        onClose = appViewModel::closeSettings,
                    )
                }
                // Settings / reload path: same brand prep UI as Model Setup.
                if (appState.isPreparingModel) {
                    Surface(modifier = Modifier.fillMaxSize(), color = OrynodeColors.paper) {
                        OnboardingStageLayout(
                            statusMessage = appState.prepStatusMessage,
                            showsProgress = true,
                        ) {}
                    }
                }
            }
        }
    }
}
