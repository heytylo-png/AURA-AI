package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.IdentityScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TasksScreen
import com.example.viewmodel.AuraViewModel

@Composable
fun MainScreen(viewModel: AuraViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val pendingApprovals by viewModel.pendingApprovalCount.collectAsState()
    val allMemories by viewModel.allMemories.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()

    // BackHandler: If settings is open, close it; otherwise if on secondary tab, back returns to Dashboard
    BackHandler(enabled = showSettings || selectedTab != 0) {
        if (showSettings) {
            viewModel.setShowSettings(false)
        } else {
            viewModel.setSelectedTab(0)
        }
    }

    if (showSettings) {
        SettingsScreen(
            viewModel = viewModel,
            onBack = { viewModel.setShowSettings(false) }
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("bottom_nav_bar")
            ) {
                // Tab 0: Dashboard (Primary Home)
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )

                // Tab 1: Chat
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                    label = { Text("Chat") },
                    modifier = Modifier.testTag("nav_tab_chat")
                )

                // Tab 2: Projects
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { viewModel.setSelectedTab(2) },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Projects") },
                    label = { Text("Projects") },
                    modifier = Modifier.testTag("nav_tab_projects")
                )

                // Tab 3: Tasks & Approvals
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { viewModel.setSelectedTab(3) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingApprovals > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ) {
                                        Text("$pendingApprovals")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Assignment, contentDescription = "Tasks")
                        }
                    },
                    label = { Text("Tasks") },
                    modifier = Modifier.testTag("nav_tab_tasks")
                )

                // Tab 4: Memory
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { viewModel.setSelectedTab(4) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (allMemories.isNotEmpty()) {
                                    Badge { Text("${allMemories.size}") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = "Memory")
                        }
                    },
                    label = { Text("Memory") },
                    modifier = Modifier.testTag("nav_tab_memory")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToChat = { viewModel.setSelectedTab(1) },
                    onNavigateToProjects = { viewModel.setSelectedTab(2) },
                    onNavigateToTasks = { viewModel.setSelectedTab(3) },
                    onNavigateToMemory = { viewModel.setSelectedTab(4) },
                    onNavigateToIdentity = { viewModel.setSelectedTab(5) }
                )
                1 -> ChatScreen(viewModel = viewModel)
                2 -> ProjectsScreen(
                    viewModel = viewModel,
                    onNavigateToChat = { viewModel.setSelectedTab(1) }
                )
                3 -> TasksScreen(viewModel = viewModel)
                4 -> MemoryScreen(viewModel = viewModel)
                5 -> IdentityScreen(viewModel = viewModel)
            }
        }
    }
}
