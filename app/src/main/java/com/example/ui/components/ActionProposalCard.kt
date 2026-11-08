package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.data.model.ProjectEntity
import com.example.data.model.ActionType
import com.example.ai.CloudProposals
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.actions.ActionExecutor
import com.example.data.model.ActionStatus
import com.example.data.model.TaskActionEntity

@Composable
fun ActionProposalCard(
    action: TaskActionEntity,
    onApprove: (TaskActionEntity) -> Unit,
    onDismiss: (TaskActionEntity) -> Unit,
    modifier: Modifier = Modifier,
    /** The card came from a Claude reply (not Aura's on-phone keyword rules). */
    proposedByClaude: Boolean = false,
    /** For "Add milestone": the projects the user can pick from. */
    projects: List<ProjectEntity> = emptyList(),
    /** Why approving failed, when known. */
    failureReason: String? = null
) {
    val isPending = action.status == ActionStatus.PROPOSED
    val isExecuted = action.status == ActionStatus.APPROVED_EXECUTED
    // Approve is offered only for action types that can actually run.
    val canExecute = ActionExecutor.isSupported(action.actionType)

    // Editable fields while the action waits for approval: what you approve is exactly what's saved.
    val editable = isPending && canExecute
    val initial = remember(action.id) { CloudProposals.editableFields(action) }
    var primary by remember(action.id) { mutableStateOf(initial.first) }
    var secondary by remember(action.id) { mutableStateOf(initial.second) }
    var projectId by remember(action.id) { mutableStateOf(action.associatedProjectId) }
    val edited = if (editable) CloudProposals.withEdits(action, primary, secondary, projectId) else null

    val cardBorder = when (action.status) {
        ActionStatus.PROPOSED -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        ActionStatus.EXECUTING -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        ActionStatus.APPROVED_EXECUTED -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ActionStatus.FAILED -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
        ActionStatus.DISMISSED -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("action_proposal_card_${action.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        border = cardBorder
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header row with Action Type chip and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = action.actionType.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isExecuted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Executed",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Saved",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (action.status == ActionStatus.FAILED) {
                    Text(
                        text = "Failed \u2014 nothing was changed",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("action_failed_label_${action.id}")
                    )
                } else if (action.status == ActionStatus.EXECUTING) {
                    Text(
                        text = "Running\u2026",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                } else if (isPending && proposedByClaude) {
                    Text(
                        text = "Proposed by Claude \u00b7 nothing has changed yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.testTag("action_proposed_by_claude_${action.id}")
                    )
                } else if (!isPending) {
                    Text(
                        text = "Dismissed",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Action title & description
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (editable && action.actionType in EDITABLE_TYPES) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = primary,
                    onValueChange = { primary = it },
                    label = { Text(primaryLabel(action.actionType)) },
                    modifier = Modifier.fillMaxWidth().testTag("action_edit_primary_${action.id}")
                )
                if (action.actionType == ActionType.CREATE_PROJECT || action.actionType == ActionType.CREATE_TODO) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = secondary,
                        onValueChange = { secondary = it },
                        label = { Text(if (action.actionType == ActionType.CREATE_TODO) "Notes (optional)" else "Description") },
                        modifier = Modifier.fillMaxWidth().testTag("action_edit_secondary_${action.id}")
                    )
                }
                if (action.actionType == ActionType.ADD_MILESTONE) {
                    Spacer(Modifier.height(6.dp))
                    ProjectPicker(projects = projects, selectedId = projectId, onPick = { projectId = it }, actionId = action.id)
                }
                if (edited == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (action.actionType == ActionType.ADD_MILESTONE && projectId == null) "Pick a project to approve."
                        else "Check the text: it's empty or too long.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (action.description.isNotBlank() && action.description != action.title) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = action.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (action.status == ActionStatus.FAILED && failureReason != null) {
                Spacer(Modifier.height(8.dp))
                NoticeBox(MessageNotice.Notice(MessageNotice.Kind.ERROR, "Not saved", failureReason))
            }

            // Reasoning notice
            if (action.reasoning.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = action.reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }

            // Action buttons if pending approval
            if (isPending) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { onDismiss(action) },
                        modifier = Modifier.testTag("dismiss_action_btn_${action.id}")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Dismiss", fontSize = 12.sp)
                    }

                    Spacer(Modifier.width(8.dp))

                    if (!canExecute) {
                        Text(
                            text = "Not available yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .testTag("action_unavailable_${action.id}")
                        )
                    } else Button(
                        onClick = { edited?.let(onApprove) },
                        enabled = edited != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("approve_action_btn_${action.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Approve",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private val EDITABLE_TYPES = setOf(ActionType.SAVE_MEMORY, ActionType.CREATE_PROJECT, ActionType.ADD_MILESTONE, ActionType.CREATE_TODO)

private fun primaryLabel(type: ActionType): String = when (type) {
    ActionType.SAVE_MEMORY -> "Memory"
    ActionType.CREATE_PROJECT -> "Project title"
    ActionType.ADD_MILESTONE -> "Milestone"
    ActionType.CREATE_TODO -> "To-do"
    else -> "Text"
}

@Composable
private fun ProjectPicker(projects: List<ProjectEntity>, selectedId: Long?, onPick: (Long) -> Unit, actionId: Long) {
    var open by remember { mutableStateOf(false) }
    val selected = projects.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("action_pick_project_$actionId")) {
            Text(
                text = selected?.title ?: if (projects.isEmpty()) "No projects yet: create one first" else "Choose a project",
                fontSize = 13.sp
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            projects.forEach { project ->
                DropdownMenuItem(text = { Text(project.title) }, onClick = { onPick(project.id); open = false })
            }
        }
    }
}
