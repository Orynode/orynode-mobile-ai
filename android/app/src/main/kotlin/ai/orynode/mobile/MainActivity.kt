package ai.orynode.mobile

import ai.orynode.mobile.app.composition.KnowledgeBaseComposition
import ai.orynode.mobile.app.ui.AppViewModel
import ai.orynode.mobile.app.ui.KnowledgeBaseViewModel
import ai.orynode.mobile.app.ui.RootScreen
import ai.orynode.mobile.app.ui.chat.KnowledgeChatHistoryStore
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import java.nio.file.Files

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash shows a complete logo mark; Compose then shows logo + titles.
        installSplashScreen().setOnExitAnimationListener { provider -> provider.remove() }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Paper window + bars: SAF resume must not flash a black buffer before Compose paints.
        window.setBackgroundDrawableResource(R.color.orynode_paper)
        @Suppress("DEPRECATION")
        run {
            val paper = android.graphics.Color.parseColor("#F6F8FD")
            window.statusBarColor = paper
            window.navigationBarColor = paper
            window.decorView.setBackgroundColor(paper)
        }
        val service = KnowledgeBaseComposition.makeService(applicationContext)
        val historyDir = filesDir.toPath().resolve("KnowledgeChat")
        Files.createDirectories(historyDir)
        val historyStore = KnowledgeChatHistoryStore(historyDir.resolve("sessions.json"))
        setContent {
            OrynodeAppTheme {
                val appViewModel: AppViewModel = viewModel(factory = AppViewModel.Factory(service))
                val knowledgeViewModel: KnowledgeBaseViewModel = viewModel(
                    factory = KnowledgeBaseViewModel.Factory(service, historyStore),
                )
                RootScreen(appViewModel, knowledgeViewModel)
            }
        }
    }
}

@Composable
private fun OrynodeAppTheme(content: @Composable () -> Unit) {
    // Force light brand surface — matches iOS preferredColorScheme(.light).
    @Suppress("UNUSED_VARIABLE")
    val ignoreDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3B7BEA),
            background = Color(0xFFF6F8FD),
            surface = Color(0xFFF6F8FD),
            onPrimary = Color.White,
            onBackground = Color(0xFF12182A),
            onSurface = Color(0xFF12182A),
        ),
        content = content,
    )
}
