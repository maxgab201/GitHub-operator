package com.maxgab.ghai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maxgab.ghai.ui.MainViewModel
import com.maxgab.ghai.ui.chat.ChatScreen
import com.maxgab.ghai.ui.settings.SettingsScreen
import com.maxgab.ghai.ui.theme.GhAiTheme

private enum class Screen { CHAT, SETTINGS }

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val app = application as GhAiApp
        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(app))
            val state by viewModel.state.collectAsStateWithLifecycle()

            GhAiTheme(appTheme = state.settings.theme) {
                var screen by remember { mutableStateOf(Screen.CHAT) }

                AnimatedContent(
                    targetState = screen,
                    label = "screen-transition",
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) }
                ) { current ->
                    when (current) {
                        Screen.CHAT -> ChatScreen(
                            state = state,
                            viewModel = viewModel,
                            onOpenSettings = { screen = Screen.SETTINGS },
                            modifier = Modifier
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            state = state,
                            viewModel = viewModel,
                            onBack = { screen = Screen.CHAT }
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
