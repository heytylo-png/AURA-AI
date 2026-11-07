package com.example.actions

import android.util.Log
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.model.ActionStatus
import com.example.data.model.ActionType
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.MemorySource
import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.ProjectStatus
import com.example.data.model.TaskActionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Outcome of an approval attempt. The stored action status always matches it. */
sealed interface ActionResult {
    /** The operation completed and the action is recorded as APPROVED_EXECUTED. */
    data object Succeeded : ActionResult

    /** The operation did not complete; nothing it would have written was kept. Recorded as FAILED. */
    data class Failed(val reason: String) : ActionResult

    /** The action was not PROPOSED (already running, done, failed or dismissed). Nothing was run. */
    data object NotPending : ActionResult
}

/**
 * Executes user-approved actions and records an honest status.
 *
 * Rules:
 * - Only a PROPOSED action can run. It is claimed (PROPOSED -> EXECUTING) with a single
 *   conditional update, so a double tap or a second screen cannot run it twice.
 * - The action's writes and its APPROVED_EXECUTED status are committed in one transaction.
 *   If anything fails, the writes are rolled back and the action is marked FAILED.
 * - Action types without an implementation are marked FAILED, never "executed".
 * - An EXECUTING action left behind by a killed process had its transaction rolled back,
 *   so [recoverInterrupted] marks it FAILED at startup.
 */
class ActionExecutor(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val taskActionDao = db.taskActionDao()
    private val memoryDao = db.memoryDao()
    private val projectDao = db.projectDao()

    suspend fun execute(actionId: Long): ActionResult {
        if (taskActionDao.claimForExecution(actionId) == 0) return ActionResult.NotPending

        val action = taskActionDao.getActionById(actionId)
            ?: return ActionResult.Failed("Action no longer exists.")

        if (!isSupported(action.actionType)) {
            markFailed(actionId)
            return ActionResult.Failed("${action.actionType.title} is not available yet.")
        }

        return try {
            db.withTransaction {
                perform(action)
                taskActionDao.updateActionStatus(actionId, ActionStatus.APPROVED_EXECUTED, clock())
            }
            ActionResult.Succeeded
        } catch (e: CancellationException) {
            withContext(NonCancellable) { markFailed(actionId) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Action $actionId (${action.actionType}) failed", e)
            markFailed(actionId)
            ActionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Marks actions left in EXECUTING by an interrupted run as FAILED. Returns how many. */
    suspend fun recoverInterrupted(): Int = taskActionDao.failInterruptedExecutions()

    private suspend fun markFailed(actionId: Long) {
        taskActionDao.updateActionStatus(actionId, ActionStatus.FAILED, null)
    }

    private suspend fun perform(action: TaskActionEntity) {
        val payload = JSONObject(action.payloadJson)
        when (action.actionType) {
            ActionType.SAVE_MEMORY -> {
                val content = payload.optString("content").ifBlank { action.description }.trim()
                require(content.isNotEmpty()) { "Memory text is empty." }
                val category = runCatching { MemoryCategory.valueOf(payload.optString("category")) }
                    .getOrDefault(MemoryCategory.PREFERENCE)
                memoryDao.insertMemory(
                    MemoryEntity(
                        content = content,
                        category = category,
                        importance = payload.optInt("importance", 3).coerceIn(1, 5),
                        isActive = true,
                        source = MemorySource.CHAT_APPROVED
                    )
                )
            }
            ActionType.CREATE_PROJECT -> {
                val title = payload.optString("title").ifBlank { action.title }.trim()
                require(title.isNotEmpty()) { "Project title is empty." }
                val projectId = projectDao.insertProject(
                    ProjectEntity(
                        title = title,
                        description = payload.optString("description", action.description),
                        category = payload.optString("category", "General"),
                        status = ProjectStatus.PLANNING
                    )
                )
                projectDao.insertMilestone(
                    MilestoneEntity(projectId = projectId, title = "Kickoff & Requirements Discovery", isCompleted = false)
                )
            }
            ActionType.ADD_MILESTONE -> {
                val projectId = if (payload.has("projectId") && !payload.isNull("projectId")) {
                    payload.getLong("projectId")
                } else {
                    action.associatedProjectId
                }
                requireNotNull(projectId) { "No project was given for the milestone." }
                requireNotNull(projectDao.getProjectById(projectId)) { "Project $projectId no longer exists." }
                val title = payload.optString("title").ifBlank { action.description }.trim()
                require(title.isNotEmpty()) { "Milestone title is empty." }
                projectDao.insertMilestone(MilestoneEntity(projectId = projectId, title = title, isCompleted = false))
            }
            else -> error("${action.actionType.title} is not available yet.")
        }
    }

    companion object {
        private const val TAG = "ActionExecutor"

        private val SUPPORTED = setOf(ActionType.SAVE_MEMORY, ActionType.CREATE_PROJECT, ActionType.ADD_MILESTONE)

        /** Whether approving this type can actually do anything. The UI hides Approve otherwise. */
        fun isSupported(type: ActionType): Boolean = type in SUPPORTED
    }
}
