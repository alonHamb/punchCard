package com.punchcard.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.punchcard.app.ui.theme.BrandBg
import com.punchcard.app.ui.theme.PunchCardTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PunchCardTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BrandBg) {
                    AppRoot(viewModel)
                }
            }
        }
    }
}

private enum class Screen { Main, Settings, Manage }

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    var screen by remember { mutableStateOf(Screen.Main) }

    when (screen) {
        Screen.Main -> MainScreen(
            viewModel = viewModel,
            onOpenSettings = { screen = Screen.Settings },
            onOpenManage = { screen = Screen.Manage },
        )
        Screen.Settings -> SettingsScreen(viewModel = viewModel, onClose = { screen = Screen.Main })
        Screen.Manage -> ManageScreen(viewModel = viewModel, onClose = { screen = Screen.Main })
    }
}
