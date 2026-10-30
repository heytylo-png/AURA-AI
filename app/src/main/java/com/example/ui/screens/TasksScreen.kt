package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ActionStatus
import com.example.ui.components.ActionProposalCard
import com.example.viewmodel.AuraViewModel

@Composable
fun TasksScreen(viewModel: AuraViewModel) {
    val allActions by viewModel.allTaskActions.collectAsState()
    val pendingCount by viewModel.pendingApprovalCount.collectAsState()

    var statusFilter by remember { mutableStateOf<ActionStatus?>(null) }

    val filteredActions = remember(allActions, statusFilter) {
        if (statusFilter == null) allActions else allActions.filter { it.status == statusFilter }
    }

    val pendingActions = remember(allActions) {
        allActions.filter { it.status == ActionStatus.PROPOSED }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Action Approvals & Tasks",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (pendingCount > 0)
                                "$pendingCount actions awaiting your approval before execution"
                            else
                                "All AI actions approved and logged",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pendingCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.AssignmentTurnedIn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Status Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            label = "All (${allActions.size})",
                            isSelected = statusFilter == null,
                            onClick = { statusFilter = null }
                        )
                    }
                    item {
                        FilterChip(
                            label = "Pending (${pendingActions.size})",
                            isSelected = statusFilter == ActionStatus.PROPOSED,
                            onClick = { statusFilter = ActionStatus.PROPOSED }
                        )
                    }
                    item {
                        FilterChip(
                            label = "Executed (${allActions.count { it.status == ActionStatus.APPROVED_EXECUTED }})",
                            isSelected = statusFilter == ActionStatus.APPROVED_EXECUTED,
                            onClick = { statusFilter = ActionStatus.APPROVED_EXECUTED }
                        )
                    }
                    item {
                        FilterChip(
                            label = "Dismissed (${allActions.count { it.status == ActionStatus.DISMISSED }})",
                            isSelected = statusFilter == ActionStatus.DISMISSED,
                            onClick = { statusFilter = ActionStatus.DISMISSED }
                        )
                    }
                }
            }
        }

        // Action cards list
        if (filteredActions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (statusFilter == ActionStatus.PROPOSED) "No actions pending approval" else "No actions in this category",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "When Aura recommends creating projects, storing memories, or scheduling tasks, you will review and approve them here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // If viewing All or Pending, highlight pending items first
                if (statusFilter == null && pendingActions.isNotEmpty()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "Awaiting Your Approval",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                items(filteredActions, key = { it.id }) { action ->
                    ActionProposalCard(
                        action = action,
                        onApprove = { viewModel.approveAction(it) },
                        onDismiss = { viewModel.dismissAction(it) }
                    )
                }
            }
        }
    }
}
