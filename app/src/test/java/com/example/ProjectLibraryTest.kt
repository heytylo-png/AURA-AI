package com.example

import com.example.ai.AuraPrompt
import com.example.ai.CloudProjects
import com.example.data.model.AiIdentityConfig
import com.example.data.model.MilestoneEntity
import com.example.data.model.NoteImport
import com.example.data.model.ProjectArtifactEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.TodoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What a shared project sends to Claude, and which files can become notes. */
class ProjectLibraryTest {
    private val project = ProjectEntity(id = 7, title = "Garden", description = "Raised beds for spring")

    private fun note(id: Long, title: String, content: String, createdAt: Long, projectId: Long = 7) =
        ProjectArtifactEntity(id = id, projectId = projectId, title = title, content = content, createdAt = createdAt)

    @Test fun onlyThisProjectsItemsNewestNotesFirstWithinBudget() {
        val big = "x".repeat(CloudProjects.MAX_NOTE_CHARS + 500)
        val notes = listOf(
            note(1, "Old", "old plan", createdAt = 1),
            note(2, "New", big, createdAt = 5),
            note(3, "Other project", "secret", createdAt = 9, projectId = 8)
        )
        val todos = listOf(
            TodoEntity(id = 1, title = "Buy soil", projectId = 7),
            TodoEntity(id = 2, title = "Done one", projectId = 7, done = true),
            TodoEntity(id = 3, title = "Elsewhere", projectId = 8)
        )
        val milestones = listOf(MilestoneEntity(id = 1, projectId = 7, title = "Build beds", isCompleted = true))
        val p = CloudProjects.from(project, milestones, todos, notes)

        assertEquals(listOf("New", "Old"), p.notes.map { it.title })
        assertTrue(p.notes[0].clipped)
        assertEquals(CloudProjects.MAX_NOTE_CHARS, p.notes[0].content.length)
        assertFalse(p.notes[1].clipped)
        assertEquals(listOf("Buy soil"), p.openTodos.map { it.title })
        assertEquals(listOf("Build beds" to true), p.milestones)
    }

    @Test fun notesBeyondBudgetAreNamedAsLeftOut() {
        val notes = (1..6).map { note(it.toLong(), "N$it", "y".repeat(CloudProjects.MAX_NOTE_CHARS), createdAt = it.toLong()) }
        val p = CloudProjects.from(project, emptyList(), emptyList(), notes)
        val sent = p.notes.sumOf { it.content.length }
        assertTrue(sent <= CloudProjects.MAX_NOTES_CHARS)
        assertTrue(p.notesLeftOut.isNotEmpty())
        assertEquals(6, p.notes.size + p.notesLeftOut.size)
        assertEquals("N6", p.notes.first().title)
    }

    @Test fun promptShowsSharedProjectAsDataAndNotOtherwise() {
        val shared = CloudProjects.from(project, emptyList(), emptyList(), listOf(note(1, "Plan", "Tomatoes on the south side", 1)))
        val withProject = AuraPrompt.system(AiIdentityConfig(), sharedProject = shared)
        assertTrue(withProject.contains("[PROJECT THE USER IS WORKING IN"))
        assertTrue(withProject.contains("Tomatoes on the south side"))
        assertFalse(withProject.contains("You can see only this conversation"))
        val without = AuraPrompt.system(AiIdentityConfig())
        assertFalse(without.contains("Tomatoes"))
        assertTrue(without.contains("You can see only this conversation"))
    }

    @Test fun textFilesImportAndBinaryFilesAreRefused() {
        assertEquals("hello\nworld", NoteImport.decode("﻿hello\r\nworld".toByteArray()))
        val pdfLike = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x00, 0x01)
        try { NoteImport.decode(pdfLike); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("text file")) }
        try { NoteImport.decode(byteArrayOf(0xC3.toByte(), 0x28)); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("text file")) }
        try { NoteImport.decode(ByteArray(NoteImport.MAX_BYTES + 1) { 'a'.code.toByte() }); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("200 KB")) }
    }
}
