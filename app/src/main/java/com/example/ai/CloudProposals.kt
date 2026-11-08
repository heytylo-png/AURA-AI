package com.example.ai

import com.example.data.model.ActionStatus
import com.example.data.model.ActionType
import com.example.data.model.MemoryCategory
import com.example.data.model.ProjectEntity
import com.example.data.model.TaskActionEntity
import com.example.data.model.TodoRules
import org.json.JSONObject

/** A tool call Claude made in a reply: the tool's name and its input as JSON text. */
data class ToolCall(val name: String, val inputJson: String)

/**
 * Turns a proposal from Claude into an action card, after checking every field on the phone.
 *
 * The relay defines the tools (relay/src/index.js, PROPOSAL_TOOLS); these limits mirror its
 * schemas and are checked again here, because nothing from the network is trusted. A proposal
 * that fails a check is dropped with a reason; it never becomes a card.
 */
object CloudProposals {
    const val MAX_MEMORY_CHARS = 500
    const val MAX_PROJECT_TITLE = 80
    const val MAX_PROJECT_DESCRIPTION = 1000
    const val MAX_MILESTONE_TITLE = 120
    const val MAX_REASON = 300

    sealed interface Result {
        data class Card(val action: TaskActionEntity) : Result
        data class Dropped(val problem: String) : Result
    }

    fun toAction(call: ToolCall, projects: List<ProjectEntity>, now: Long = System.currentTimeMillis()): Result {
        val input = runCatching { JSONObject(call.inputJson.ifBlank { "{}" }) }.getOrNull()
            ?: return Result.Dropped("its details weren't valid JSON")
        val reason = input.optString("reason").trim().take(MAX_REASON)
        return when (call.name) {
            "propose_save_memory" -> {
                val text = input.optString("text").trim()
                val category = runCatching { MemoryCategory.valueOf(input.optString("category")) }.getOrNull()
                val importance = if (input.has("importance")) input.optInt("importance", -1) else -1
                when {
                    text.isEmpty() -> Result.Dropped("the memory text was empty")
                    text.length > MAX_MEMORY_CHARS -> Result.Dropped("the memory text was over $MAX_MEMORY_CHARS characters")
                    category == null -> Result.Dropped("the memory category wasn't one Aura knows")
                    importance !in 1..5 -> Result.Dropped("the importance wasn't between 1 and 5")
                    else -> Result.Card(
                        TaskActionEntity(
                            title = "Save memory",
                            description = text,
                            actionType = ActionType.SAVE_MEMORY,
                            reasoning = reason,
                            payloadJson = memoryPayload(text, category, importance),
                            status = ActionStatus.PROPOSED,
                            createdAt = now
                        )
                    )
                }
            }
            "propose_create_project" -> {
                val title = input.optString("title").trim()
                val description = input.optString("description").trim()
                when {
                    title.isEmpty() -> Result.Dropped("the project title was empty")
                    title.length > MAX_PROJECT_TITLE -> Result.Dropped("the project title was over $MAX_PROJECT_TITLE characters")
                    description.length > MAX_PROJECT_DESCRIPTION -> Result.Dropped("the project description was too long")
                    else -> Result.Card(
                        TaskActionEntity(
                            title = "Create project",
                            description = title,
                            actionType = ActionType.CREATE_PROJECT,
                            reasoning = reason,
                            payloadJson = projectPayload(title, description),
                            status = ActionStatus.PROPOSED,
                            createdAt = now
                        )
                    )
                }
            }
            "propose_add_milestone" -> {
                val milestone = input.optString("milestone_title").trim()
                val projectName = input.optString("project_title").trim()
                when {
                    milestone.isEmpty() -> Result.Dropped("the milestone title was empty")
                    milestone.length > MAX_MILESTONE_TITLE -> Result.Dropped("the milestone title was over $MAX_MILESTONE_TITLE characters")
                    else -> {
                        // Claude can't see projects: pre-select only an exact (case-insensitive) name match.
                        val match = projects.singleOrNull { it.title.trim().equals(projectName, ignoreCase = true) }
                        Result.Card(
                            TaskActionEntity(
                                title = "Add milestone",
                                description = milestone,
                                actionType = ActionType.ADD_MILESTONE,
                                reasoning = reason,
                                payloadJson = milestonePayload(milestone, match?.id, projectName),
                                status = ActionStatus.PROPOSED,
                                associatedProjectId = match?.id,
                                createdAt = now
                            )
                        )
                    }
                }
            }
            "propose_add_todo" -> {
                val cleaned = TodoRules.clean(input.optString("title"), input.optString("notes"))
                val dueText = input.optString("due_date").trim()
                val due = runCatching { TodoRules.parseDue(dueText) }
                val projectName = input.optString("project_title").trim()
                when {
                    cleaned == null -> Result.Dropped("the to-do title was empty or too long, or its notes were too long")
                    due.isFailure -> Result.Dropped("the due date \"${dueText.take(20)}\" isn't a real date")
                    else -> {
                        val match = if (projectName.isEmpty()) null
                        else projects.singleOrNull { it.title.trim().equals(projectName, ignoreCase = true) }
                        val payload = JSONObject()
                            .put("title", cleaned.first)
                            .put("notes", cleaned.second)
                            .put("dueDate", due.getOrNull()?.let { TodoRules.formatDue(it) } ?: JSONObject.NULL)
                            .put("projectId", match?.id ?: JSONObject.NULL)
                            .put("projectTitle", projectName)
                            .put("origin", "claude")
                        Result.Card(
                            TaskActionEntity(
                                title = "Add to-do",
                                description = cleaned.first,
                                actionType = ActionType.CREATE_TODO,
                                reasoning = reason,
                                payloadJson = payload.toString(),
                                status = ActionStatus.PROPOSED,
                                associatedProjectId = match?.id,
                                createdAt = now
                            )
                        )
                    }
                }
            }
            else -> Result.Dropped("Aura doesn't support \"${call.name.take(40)}\"")
        }
    }

    /**
     * Applies the user's edits from the card. Returns null when an edit breaks a limit
     * (the card keeps Approve disabled until it's fixed).
     */
    fun withEdits(action: TaskActionEntity, primary: String, secondary: String, projectId: Long?): TaskActionEntity? {
        val main = primary.trim()
        val extra = secondary.trim()
        val old = runCatching { JSONObject(action.payloadJson) }.getOrElse { JSONObject() }
        return when (action.actionType) {
            ActionType.SAVE_MEMORY -> {
                if (main.isEmpty() || main.length > MAX_MEMORY_CHARS) return null
                val category = runCatching { MemoryCategory.valueOf(old.optString("category")) }.getOrDefault(MemoryCategory.PREFERENCE)
                action.copy(description = main, payloadJson = memoryPayload(main, category, old.optInt("importance", 3).coerceIn(1, 5)))
            }
            ActionType.CREATE_PROJECT -> {
                if (main.isEmpty() || main.length > MAX_PROJECT_TITLE || extra.length > MAX_PROJECT_DESCRIPTION) return null
                action.copy(description = main, payloadJson = projectPayload(main, extra, old.optString("category", "General")))
            }
            ActionType.ADD_MILESTONE -> {
                if (main.isEmpty() || main.length > MAX_MILESTONE_TITLE || projectId == null) return null
                action.copy(
                    description = main,
                    associatedProjectId = projectId,
                    payloadJson = milestonePayload(main, projectId, old.optString("projectTitle"))
                )
            }
            ActionType.CREATE_TODO -> {
                val (title, notes) = TodoRules.clean(main, extra) ?: return null
                val payload = JSONObject(old.toString())
                    .put("title", title).put("notes", notes).put("projectId", projectId ?: JSONObject.NULL)
                action.copy(description = title, associatedProjectId = projectId, payloadJson = payload.toString())
            }
            else -> null
        }
    }

    /** The fields a card lets the user edit: (main text, second text). */
    fun editableFields(action: TaskActionEntity): Pair<String, String> {
        val p = runCatching { JSONObject(action.payloadJson) }.getOrElse { JSONObject() }
        return when (action.actionType) {
            ActionType.SAVE_MEMORY -> p.optString("content", action.description) to ""
            ActionType.CREATE_PROJECT -> p.optString("title", action.description) to p.optString("description")
            ActionType.ADD_MILESTONE -> p.optString("title", action.description) to p.optString("projectTitle")
            ActionType.CREATE_TODO -> p.optString("title", action.description) to p.optString("notes")
            else -> action.description to ""
        }
    }

    private fun memoryPayload(text: String, category: MemoryCategory, importance: Int) = JSONObject()
        .put("content", text).put("category", category.name).put("importance", importance).toString()

    private fun projectPayload(title: String, description: String, category: String = "General") = JSONObject()
        .put("title", title).put("description", description).put("category", category).toString()

    private fun milestonePayload(title: String, projectId: Long?, projectTitle: String) = JSONObject()
        .put("title", title).put("projectId", projectId ?: JSONObject.NULL).put("projectTitle", projectTitle).toString()

    /** The due date on a to-do card (day number), or null. */
    fun dueOf(action: TaskActionEntity): Long? {
        val p = runCatching { JSONObject(action.payloadJson) }.getOrNull() ?: return null
        if (!p.has("dueDate") || p.isNull("dueDate")) return null
        return runCatching { TodoRules.parseDue(p.optString("dueDate")) }.getOrNull()
    }

    /** Sets or clears the due date on a to-do card. */
    fun withDue(action: TaskActionEntity, dueEpochDay: Long?): TaskActionEntity {
        if (action.actionType != ActionType.CREATE_TODO) return action
        val p = runCatching { JSONObject(action.payloadJson) }.getOrElse { JSONObject() }
        p.put("dueDate", dueEpochDay?.let { TodoRules.formatDue(it) } ?: JSONObject.NULL)
        return action.copy(payloadJson = p.toString())
    }
}
