package com.example.ai

import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectArtifactEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.TodoEntity
import com.example.data.model.TodoRules

/** What Claude may see of a project the user chose to share, while chatting in that project. */
data class CloudProject(
    val title: String,
    val status: String,
    val description: String,
    /** Milestone titles with done/not done. */
    val milestones: List<Pair<String, Boolean>>,
    val openTodos: List<CloudTodo>,
    val notes: List<CloudNote>,
    /** Titles of notes that didn't fit in the size budget (so Claude can say it can't see them). */
    val notesLeftOut: List<String>
)

data class CloudNote(val title: String, val content: String, val clipped: Boolean)

object CloudProjects {
    /** Most characters of notes sent in one request (the relay also caps the whole system prompt). */
    const val MAX_NOTES_CHARS = 18_000
    const val MAX_NOTE_CHARS = 6_000
    const val MAX_MILESTONES = 30
    const val MAX_TODOS = 30
    const val MAX_DESCRIPTION = 1_500

    fun from(
        project: ProjectEntity,
        milestones: List<MilestoneEntity>,
        todos: List<TodoEntity>,
        notes: List<ProjectArtifactEntity>
    ): CloudProject {
        var budget = MAX_NOTES_CHARS
        val sent = mutableListOf<CloudNote>()
        val leftOut = mutableListOf<String>()
        // Newest notes first: they are usually the most relevant to what's happening now.
        for (note in notes.filter { it.projectId == project.id }.sortedByDescending { it.createdAt }) {
            val room = minOf(MAX_NOTE_CHARS, budget)
            if (room < 200) { leftOut += note.title; continue }
            val clipped = note.content.length > room
            val content = if (clipped) note.content.take(room) else note.content
            sent += CloudNote(note.title.take(120), content, clipped)
            budget -= content.length + note.title.length
        }
        return CloudProject(
            title = project.title.take(120),
            status = project.status.label,
            description = project.description.take(MAX_DESCRIPTION),
            milestones = milestones.filter { it.projectId == project.id }.take(MAX_MILESTONES).map { it.title.take(120) to it.isCompleted },
            openTodos = todos.filter { it.projectId == project.id && !it.done && it.deletedAt == null }
                .sortedWith(TodoRules.openOrder).take(MAX_TODOS)
                .map { CloudTodo(it.title, it.dueEpochDay?.let(TodoRules::formatDue)) },
            notes = sent,
            notesLeftOut = leftOut
        )
    }
}
