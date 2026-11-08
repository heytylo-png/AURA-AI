package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.CloudProposals
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.ai.ToolCall
import com.example.data.model.ActionStatus
import com.example.data.model.ActionType
import com.example.data.model.AiIdentityConfig
import com.example.data.model.MemoryCategory
import com.example.data.model.ProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A change Claude proposes becomes a card only after every field passes the phone's checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClaudeProposalsTest {

    private val garden = ProjectEntity(id = 7, title = "Garden", description = "")

    private fun card(call: ToolCall, projects: List<ProjectEntity> = emptyList()) =
        (CloudProposals.toAction(call, projects) as CloudProposals.Result.Card).action

    private fun dropped(call: ToolCall) = CloudProposals.toAction(call, emptyList()) as CloudProposals.Result.Dropped

    private fun json(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    @Test
    fun validMemoryProposalBecomesAWaitingCard() {
        val a = card(ToolCall("propose_save_memory", json("text" to " Likes tea ", "category" to "PREFERENCE", "importance" to 4, "reason" to "You said so")))
        assertEquals(ActionType.SAVE_MEMORY, a.actionType)
        assertEquals(ActionStatus.PROPOSED, a.status)
        assertEquals("Likes tea", a.description)
        assertEquals("You said so", a.reasoning)
        val p = JSONObject(a.payloadJson)
        assertEquals("Likes tea", p.getString("content"))
        assertEquals("PREFERENCE", p.getString("category"))
        assertEquals(4, p.getInt("importance"))
    }

    @Test
    fun badMemoryProposalsAreDropped() {
        assertTrue(dropped(ToolCall("propose_save_memory", json("text" to "", "category" to "FACT", "importance" to 3))).problem.contains("empty"))
        assertTrue(dropped(ToolCall("propose_save_memory", json("text" to "x".repeat(501), "category" to "FACT", "importance" to 3))).problem.contains("500"))
        assertTrue(dropped(ToolCall("propose_save_memory", json("text" to "x", "category" to "SECRET", "importance" to 3))).problem.contains("category"))
        assertTrue(dropped(ToolCall("propose_save_memory", json("text" to "x", "category" to "FACT", "importance" to 9))).problem.contains("importance"))
        assertTrue(dropped(ToolCall("propose_save_memory", "{not json")).problem.contains("JSON"))
        assertTrue(dropped(ToolCall("delete_everything", "{}")).problem.contains("doesn't support"))
    }

    @Test
    fun projectProposalChecksLengths() {
        val a = card(ToolCall("propose_create_project", json("title" to "Garden", "description" to "Raised beds", "reason" to "")))
        assertEquals(ActionType.CREATE_PROJECT, a.actionType)
        assertEquals("Raised beds", JSONObject(a.payloadJson).getString("description"))
        dropped(ToolCall("propose_create_project", json("title" to "x".repeat(81), "description" to "")))
    }

    @Test
    fun milestonePreselectsOnlyAnExactProjectMatch() {
        val matched = card(ToolCall("propose_add_milestone", json("project_title" to "garden", "milestone_title" to "Buy soil")), listOf(garden))
        assertEquals(7L, matched.associatedProjectId)
        val unmatched = card(ToolCall("propose_add_milestone", json("project_title" to "Gardening", "milestone_title" to "Buy soil")), listOf(garden))
        assertNull(unmatched.associatedProjectId)
        // Without a project the card can't be approved.
        assertNull(CloudProposals.withEdits(unmatched, "Buy soil", "", projectId = null))
        assertEquals(7L, CloudProposals.withEdits(unmatched, "Buy soil", "", projectId = 7)!!.associatedProjectId)
    }

    @Test
    fun editsAreCheckedAgain() {
        val a = card(ToolCall("propose_save_memory", json("text" to "Likes tea", "category" to "FACT", "importance" to 2)))
        val edited = CloudProposals.withEdits(a, "Likes green tea", "", null)!!
        assertEquals(a.id, edited.id)
        assertEquals("Likes green tea", JSONObject(edited.payloadJson).getString("content"))
        assertEquals("FACT", JSONObject(edited.payloadJson).getString("category"))
        assertNull(CloudProposals.withEdits(a, "  ", "", null))
        assertNull(CloudProposals.withEdits(a, "x".repeat(501), "", null))
    }

    @Test
    fun relayAndAppAgreeOnMemoryCategories() {
        val relay = listOf(File("../relay/src/index.js"), File("relay/src/index.js")).first { it.exists() }.readText()
        val line = Regex("""MEMORY_CATEGORIES = \[(.*?)]""").find(relay)!!.groupValues[1]
        val relayNames = Regex(""""([A-Z_]+)"""").findAll(line).map { it.groupValues[1] }.toList()
        assertEquals(MemoryCategory.values().map { it.name }, relayNames)
    }

    // ---- Routing ----

    private fun provider(event: AiProviderEvent) = object : AiProvider {
        var request: AiProviderRequest? = null
        override val providerId = "fake"
        override val capabilities = AiProviderCapabilities(streaming = true, supportsPersonalContext = true, supportsOffline = false)
        override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> { this.request = request; return flowOf(event) }
    }

    private fun route(event: AiProviderEvent, allow: Boolean): Pair<HybridAiState.Completed, AiProviderRequest?> = runBlocking {
        val p = provider(event)
        val engine = HybridAiEngine(ApplicationProvider.getApplicationContext(), cloudEngine = p, onlineStatusOverride = { true })
        val result = engine.processHybridRequest(
            prompt = "Please remember that I like tea",
            mode = HybridRoutingMode.FORCE_CLOUD,
            identity = AiIdentityConfig(),
            activeMemories = emptyList(),
            projects = listOf(garden),
            allowCloudRequest = true,
            allowProposals = allow
        ).toList().last() as HybridAiState.Completed
        result to p.request
    }

    private val memoryCall = ToolCall("propose_save_memory", """{"text":"Likes tea","category":"PREFERENCE","importance":3,"reason":"r"}""")

    @Test
    fun aValidProposalShowsAsACardWhenAllowed() {
        val (result, request) = route(AiProviderEvent.Completed("I've suggested saving that.", 10, toolCall = memoryCall), allow = true)
        assertTrue(request!!.allowProposals)
        assertNotNull(result.proposedAction)
        assertEquals(ActionType.SAVE_MEMORY, result.proposedAction!!.actionType)
    }

    @Test
    fun noCardWhenProposalsAreOff() {
        val (result, request) = route(AiProviderEvent.Completed("Sure.", 10, toolCall = memoryCall), allow = false)
        assertEquals(false, request!!.allowProposals)
        assertNull(result.proposedAction)
    }

    @Test
    fun aBadProposalIsDroppedAndTheDetailsSaySo() {
        val bad = ToolCall("propose_save_memory", """{"text":"","category":"PREFERENCE","importance":3}""")
        val (result, _) = route(AiProviderEvent.Completed("Here.", 10, toolCall = bad), allow = true)
        assertNull(result.proposedAction)
        assertTrue(result.reasoningRationale, result.reasoningRationale.contains("was not shown because the memory text was empty"))
    }

    @Test
    fun aProposalWithNoTextGetsAnHonestLine() {
        val (result, _) = route(AiProviderEvent.Completed("", 10, toolCall = memoryCall), allow = true)
        assertEquals("I've suggested a change below. Nothing happens unless you approve it.", result.fullText)
    }
}
