package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ai.configureAppCheck
import com.example.ui.MainScreen
import com.example.ui.theme.AuraTheme
import com.example.viewmodel.AuraViewModel

class MainActivity : ComponentActivity() {

    private val auraViewModel: AuraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory App Check initialization with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        openRequestedTab(intent)
        enableEdgeToEdge()

        setContent {
            AuraTheme(darkTheme = true) {
                MainScreen(viewModel = auraViewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedTab(intent)
    }

    /** A tapped reminder opens the Tasks tab. Only known tabs are accepted. */
    private fun openRequestedTab(intent: android.content.Intent?) {
        val tab = intent?.getIntExtra(com.example.reminders.Reminders.EXTRA_OPEN_TAB, -1) ?: -1
        if (tab == com.example.reminders.Reminders.TASKS_TAB) auraViewModel.setSelectedTab(tab)
    }
}
