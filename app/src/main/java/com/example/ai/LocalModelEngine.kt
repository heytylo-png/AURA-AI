package com.example.ai

import com.example.data.model.ActionType
import com.example.data.model.AiIdentityConfig
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.PersonalityArchetype
import com.example.data.model.ProjectEntity
import com.example.data.model.TaskActionEntity
import com.example.data.model.UserSettingsEntity
import java.util.Locale

data class LocalAiResult(
    val responseText: String,
    val proposedAction: TaskActionEntity? = null,
    val latencyMs: Long = 18L,
    val reasoningExplanation: String = "Processed via Edge Reasoning Pipeline"
)

class LocalModelEngine {

    /**
     * Executes on-device offline reasoning with intent classification,
     * memory retrieval, semantic extraction, and identity persona styling.
     */
    fun processQuery(
        prompt: String,
        identity: AiIdentityConfig,
        activeMemories: List<MemoryEntity>,
        projects: List<ProjectEntity>,
        currentProject: ProjectEntity? = null,
        userSettings: UserSettingsEntity? = null
    ): LocalAiResult {
        val startTime = System.currentTimeMillis()
        val cleanPrompt = prompt.trim()
        val lower = cleanPrompt.lowercase(Locale.ROOT)

        // 1. Check for memory storage trigger
        if (isMemorySaveIntent(lower)) {
            val extractedMemory = extractMemoryContent(cleanPrompt)
            val category = classifyMemoryCategory(lower)
            val action = TaskActionEntity(
                title = "Save Memory: \"${extractedMemory.take(45)}\"",
                description = extractedMemory,
                actionType = ActionType.SAVE_MEMORY,
                reasoning = "Aura detected a personal preference or key fact to remember permanently across sessions.",
                payloadJson = "{\"content\":\"$extractedMemory\",\"category\":\"${category.name}\",\"importance\":4}"
            )
            val text = formatMemoryAckResponse(identity, extractedMemory, category)
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(14)
            return LocalAiResult(
                responseText = text,
                proposedAction = action,
                latencyMs = latency,
                reasoningExplanation = "Edge NLP classified memory storage intent and formulated structured persistence proposal."
            )
        }

        // 2. Check for project creation trigger
        if (isProjectCreateIntent(lower)) {
            val projectName = extractProjectTitle(cleanPrompt)
            val action = TaskActionEntity(
                title = "Create Project: $projectName",
                description = "Initialize new project repository: $projectName",
                actionType = ActionType.CREATE_PROJECT,
                reasoning = "Aura identified intent to organize work under a dedicated project library entry.",
                payloadJson = "{\"title\":\"$projectName\",\"description\":\"Created via Aura task execution\",\"category\":\"Tech\"}"
            )
            val text = formatProjectAckResponse(identity, projectName)
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(16)
            return LocalAiResult(
                responseText = text,
                proposedAction = action,
                latencyMs = latency,
                reasoningExplanation = "Edge Classifier extracted project title and prepared library registration task."
            )
        }

        // 3. Check for task / action proposal trigger
        if (isTaskActionIntent(lower)) {
            val taskDesc = extractTaskContent(cleanPrompt)
            val action = TaskActionEntity(
                title = "Execute Task: \"${taskDesc.take(40)}\"",
                description = taskDesc,
                actionType = ActionType.CREATE_TODO,
                reasoning = "Aura parsed an actionable to-do item requiring human approval before adding to schedule.",
                associatedProjectId = currentProject?.id,
                payloadJson = "{\"title\":\"$taskDesc\",\"projectId\":${currentProject?.id ?: "null"}}"
            )
            val text = formatTaskAckResponse(identity, taskDesc, currentProject)
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(15)
            return LocalAiResult(
                responseText = text,
                proposedAction = action,
                latencyMs = latency,
                reasoningExplanation = "Edge Rule Engine detected an actionable commitment and queued it for approval."
            )
        }

        // 4. Check for memory recall / inspection
        if (isMemoryRecallIntent(lower)) {
            val text = formatMemoryRecallResponse(identity, activeMemories)
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(12)
            return LocalAiResult(
                responseText = text,
                latencyMs = latency,
                reasoningExplanation = "Direct on-device Room memory database retrieval."
            )
        }

        // 5. Check for project library overview query
        if (isProjectQueryIntent(lower)) {
            val text = formatProjectOverviewResponse(identity, projects, currentProject)
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(14)
            return LocalAiResult(
                responseText = text,
                latencyMs = latency,
                reasoningExplanation = "Edge query of active project library and milestone metrics."
            )
        }

        // 6. Check for identity / persona question
        if (isIdentityQueryIntent(lower)) {
            val text = formatIdentityResponse(identity)
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(10)
            return LocalAiResult(
                responseText = text,
                latencyMs = latency,
                reasoningExplanation = "Edge synthesis using configured AI identity archetype and core values."
            )
        }

        // 7. General offline intelligent synthesis
        val synthesizedText = synthesizeOfflineResponse(cleanPrompt, identity, activeMemories, currentProject, userSettings)
        val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(18)
        return LocalAiResult(
            responseText = synthesizedText,
            latencyMs = latency,
            reasoningExplanation = "Edge heuristic NLP engine operating offline with persistent memory injection."
        )
    }

    private fun isMemorySaveIntent(prompt: String): Boolean {
        return prompt.startsWith("remember that") ||
                prompt.startsWith("remember this") ||
                prompt.startsWith("keep in mind that") ||
                prompt.contains("my favorite") ||
                prompt.contains("i prefer") ||
                prompt.contains("my goal is") ||
                prompt.contains("note that i")
    }

    private fun isProjectCreateIntent(prompt: String): Boolean {
        return prompt.startsWith("create project") ||
                prompt.startsWith("start project") ||
                prompt.startsWith("new project") ||
                prompt.contains("start a new project called") ||
                prompt.contains("create a project named")
    }

    private fun isTaskActionIntent(prompt: String): Boolean {
        return prompt.startsWith("task:") ||
                prompt.startsWith("todo:") ||
                prompt.startsWith("add task") ||
                prompt.startsWith("create task") ||
                prompt.startsWith("schedule ") ||
                prompt.contains("remind me to") ||
                prompt.contains("add to my tasks")
    }

    private fun isMemoryRecallIntent(prompt: String): Boolean {
        return prompt.contains("what do you remember") ||
                prompt.contains("recall my") ||
                prompt.contains("show memories") ||
                prompt.contains("what are my preferences") ||
                prompt.contains("what do you know about me")
    }

    private fun isProjectQueryIntent(prompt: String): Boolean {
        return prompt.contains("what projects") ||
                prompt.contains("show projects") ||
                prompt.contains("project status") ||
                prompt.contains("my projects") ||
                prompt.contains("list projects")
    }

    private fun isIdentityQueryIntent(prompt: String): Boolean {
        return prompt.contains("who are you") ||
                prompt.contains("what is your identity") ||
                prompt.contains("what are your values") ||
                prompt.contains("tell me about yourself")
    }

    private fun extractMemoryContent(prompt: String): String {
        val prefixes = listOf(
            "remember that ",
            "remember this: ",
            "keep in mind that ",
            "note that ",
            "please remember "
        )
        var result = prompt
        for (p in prefixes) {
            if (result.lowercase(Locale.ROOT).startsWith(p)) {
                result = result.substring(p.length).trim()
                break
            }
        }
        return result.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun classifyMemoryCategory(lower: String): MemoryCategory {
        return when {
            lower.contains("prefer") || lower.contains("like") || lower.contains("hate") -> MemoryCategory.PREFERENCE
            lower.contains("goal") || lower.contains("target") || lower.contains("aim") -> MemoryCategory.GOAL
            lower.contains("work") || lower.contains("project") || lower.contains("stack") -> MemoryCategory.CONTEXT
            lower.contains("rule") || lower.contains("always") || lower.contains("never") -> MemoryCategory.IDENTITY_RULE
            else -> MemoryCategory.FACT
        }
    }

    private fun extractProjectTitle(prompt: String): String {
        val regex = Regex("""(?:create|start|new) project (?:named |called )?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
        val match = regex.find(prompt)
        return match?.groupValues?.get(1)?.trim() ?: "New Initiative"
    }

    private fun extractTaskContent(prompt: String): String {
        val prefixes = listOf("add task ", "create task ", "task: ", "todo: ", "remind me to ")
        var result = prompt
        for (p in prefixes) {
            if (result.lowercase(Locale.ROOT).startsWith(p)) {
                result = result.substring(p.length).trim()
                break
            }
        }
        return result
    }

    private fun formatMemoryAckResponse(identity: AiIdentityConfig, memoryText: String, category: MemoryCategory): String {
        return when (identity.archetype) {
            PersonalityArchetype.ANALYTICAL ->
                "Memory registered: \"$memoryText\" under [${category.displayName}]. I have queued an action proposal below for your confirmation before committing to persistent storage."
            PersonalityArchetype.CREATIVE_STRATEGIST ->
                "Fascinating nuance! I've captured: \"$memoryText\". I've framed this as an approved action so you retain full control over my persistent knowledge graph."
            PersonalityArchetype.DIRECT_OPERATOR ->
                "Captured: \"$memoryText\" (${category.displayName}). Action queued below for approval."
            PersonalityArchetype.EMPATHIC_MENTOR ->
                "I understand and value this about you: \"$memoryText\". I've prepared a memory card below—please tap Approve so I carry this insight with me."
        }
    }

    private fun formatProjectAckResponse(identity: AiIdentityConfig, projectName: String): String {
        return "I've structured a new project workspace for \"$projectName\". You can review and approve the creation action below to register it in your Project Library."
    }

    private fun formatTaskAckResponse(identity: AiIdentityConfig, task: String, project: ProjectEntity?): String {
        val projectNotice = if (project != null) " linked to project \"${project.title}\"" else ""
        return "Action identified: \"$task\"$projectNotice. In accordance with my user-supervision protocol, please approve the task card below to execute it."
    }

    private fun formatMemoryRecallResponse(identity: AiIdentityConfig, memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) {
            return "My persistent memory bank is currently empty. Tell me your preferences, key goals, or project guidelines (e.g. \"Remember that I prefer dark mode\"), and I will record them with your approval."
        }
        val builder = StringBuilder("Here are the active items I currently retain in persistent memory:\n\n")
        memories.take(6).forEachIndexed { index, m ->
            builder.append("${index + 1}. [${m.category.displayName}] ${m.content}\n")
        }
        if (memories.size > 6) {
            builder.append("\n...and ${memories.size - 6} more in your Memory Manager.")
        }
        return builder.toString()
    }

    private fun formatProjectOverviewResponse(identity: AiIdentityConfig, projects: List<ProjectEntity>, current: ProjectEntity?): String {
        if (projects.isEmpty()) {
            return "Your Project Library has no registered initiatives yet. Say \"Create project Mobile Redesign\" or tap '+' in the Projects tab to initiate one."
        }
        val builder = StringBuilder("Current Project Library snapshot (${projects.size} active):\n\n")
        projects.forEach { p ->
            val mark = if (p.id == current?.id) " (Active Context)" else ""
            builder.append("• ${p.title} — ${p.status.label}$mark\n  ${p.description.take(60)}\n\n")
        }
        return builder.toString().trim()
    }

    private fun formatIdentityResponse(identity: AiIdentityConfig): String {
        return "I am ${identity.name}, your hybrid personal AI partner.\n\n" +
                "• Archetype: ${identity.archetype.title}\n" +
                "• Core Values: ${identity.coreValues}\n" +
                "• Response Style: ${identity.responseLength.label}\n" +
                "• Philosophy: Autonomous task execution bounded by explicit human approval, backed by persistent local memory and hybrid edge-cloud computing."
    }

    private fun synthesizeOfflineResponse(
        prompt: String,
        identity: AiIdentityConfig,
        memories: List<MemoryEntity>,
        currentProject: ProjectEntity?,
        userSettings: UserSettingsEntity? = null
    ): String {
        val lower = prompt.lowercase()
        val userGreeting = if (!userSettings?.userName.isNullOrBlank()) " ${userSettings?.userName?.trim()}" else ""
        val interestNote = if (!userSettings?.primaryInterest.isNullOrBlank()) {
            "\n\n(Personalized for your focus in ${userSettings?.primaryInterest?.trim()})"
        } else ""
        val projectContext = if (currentProject != null) " [Project: ${currentProject.title}]" else ""
        val relevantMem = memories.firstOrNull { mem ->
            prompt.split(" ").any { word -> word.length > 3 && mem.content.contains(word, ignoreCase = true) }
        }

        val memoryInsight = if (relevantMem != null) "\n\n(Relevant Memory: \"${relevantMem.content}\")" else ""

        if (lower.contains("what can you do") || lower.contains("help") || lower.contains("features") || lower.contains("how do you work")) {
            return "Hello$userGreeting! Here is what I can do as your personal AI partner$projectContext:\n\n" +
                    "1. Persistent Memory: Tell me \"Remember that...\" or \"My preference is...\" and I will retain it across sessions.\n" +
                    "2. Project Library: Say \"Create project <title>\" to track initiatives with milestones and documents.\n" +
                    "3. Supervised Task Execution: Say \"Add task <action>\" and I will propose it for your approval before committing.\n" +
                    "4. Voice Interaction: Tap the Voice Orb or mic to speak with me; I can speak back with adjustable vocal synthesis.\n" +
                    "5. Hybrid Computing: I use fast local edge processing offline and cloud reasoning for deep analysis.$interestNote$memoryInsight"
        }

        if (lower.contains("thank") || lower.contains("great") || lower.contains("awesome") || lower.contains("good job")) {
            return when (identity.archetype) {
                PersonalityArchetype.ANALYTICAL -> "Glad to be of assistance$userGreeting. All active states and memories remain synchronized."
                PersonalityArchetype.CREATIVE_STRATEGIST -> "Always energized to collaborate with you$userGreeting! What initiative shall we tackle next?"
                PersonalityArchetype.DIRECT_OPERATOR -> "Understood$userGreeting. Standing by for next command."
                PersonalityArchetype.EMPATHIC_MENTOR -> "You're very welcome$userGreeting! It's a pleasure working alongside you."
            }
        }

        return when (identity.archetype) {
            PersonalityArchetype.ANALYTICAL ->
                "Assessment of: \"$prompt\"$projectContext\n\n" +
                        "• Processing: Evaluated via Edge Reasoning Engine.\n" +
                        "• Key Insight: To turn this into a permanent workflow, say \"Create project <name>\" or \"Add task <action>\".\n" +
                        "• Knowledge: All preferences remain preserved in your Memory tab.$memoryInsight"

            PersonalityArchetype.CREATIVE_STRATEGIST ->
                "Exploring \"$prompt\"$projectContext!\n\n" +
                        "Here's an angle: We can turn this thought into structured milestones in your Project Library. " +
                        "Would you like me to draft an action plan for your approval?$memoryInsight"

            PersonalityArchetype.DIRECT_OPERATOR ->
                "Acknowledged: \"$prompt\"$projectContext.\n\n" +
                        "Ready to act. I can create a project, queue tasks, or log key details into your persistent memory.$memoryInsight"

            PersonalityArchetype.EMPATHIC_MENTOR ->
                "I've noted this regarding \"$prompt\"$projectContext.\n\n" +
                        "I'm here to support your focus and productivity. Let me know if you want me to record any preferences or break this down into clear steps.$memoryInsight"
        }
    }
}
