package com.example

import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.ai.forCloud
import com.example.ai.withoutPersonalContext
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.UserSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderContractTest {

    private class FakeProvider(
        private val stream: Flow<AiProviderEvent>? = null
    ) : AiProvider {
        override val providerId = "test-provider"
        override val capabilities = AiProviderCapabilities(
            streaming = true,
            supportsPersonalContext = true,
            supportsOffline = false
        )
        var receivedRequest: AiProviderRequest? = null

        override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> {
            receivedRequest = request
            return stream ?: flow {
                emit(AiProviderEvent.Content(request.prompt))
                emit(AiProviderEvent.Completed(request.prompt, 1L))
            }
        }
    }

    private fun testContext() = object : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun providerRequestExcludesPersonalContextByDefault() {
        assertFalse(AiProviderRequest(prompt = "Hello", identity = com.example.data.model.AiIdentityConfig()).allowPersonalContext)
    }

    @Test
    fun cloudRequestStripsAllPersonalContextBeforeProviderReceivesIt() {
        val request = AiProviderRequest(
            prompt = "Help with my project",
            identity = com.example.data.model.AiIdentityConfig(),
            activeMemories = listOf(MemoryEntity(content = "private preference", category = MemoryCategory.PREFERENCE)),
            currentProject = ProjectEntity(title = "Private project", description = "Details"),
            userSettings = UserSettingsEntity(userName = "Private name", primaryInterest = "Private interest")
        ).withoutPersonalContext()

        assertTrue(request.activeMemories.isEmpty())
        assertNull(request.currentProject)
        assertNull(request.userSettings)
        assertFalse(request.allowPersonalContext)
    }

    @Test
    fun providerContractStreamsProviderNeutralEvents() = runBlocking {
        val provider: AiProvider = FakeProvider()
        val events = provider.generateStream(
            AiProviderRequest(
                prompt = "Hello Aura",
                identity = com.example.data.model.AiIdentityConfig()
            )
        ).toList()

        assertEquals("test-provider", provider.providerId)
        assertEquals(2, events.size)
        assertEquals(AiProviderEvent.Content("Hello Aura"), events[0])
        assertEquals(AiProviderEvent.Completed("Hello Aura", 1L), events[1])
    }

    @Test
    fun hybridRouterSendsSanitizedRequestToActualProvider() = runBlocking {
        val provider = FakeProvider()
        val engine = HybridAiEngine(
            context = testContext(),
            cloudEngine = provider,
            onlineStatusOverride = { true }
        )
        val project = ProjectEntity(title = "Private project", description = "Details")

        engine.processHybridRequest(
            prompt = "Please analyze this complex topic in detail",
            mode = HybridRoutingMode.FORCE_CLOUD,
            identity = com.example.data.model.AiIdentityConfig(),
            activeMemories = listOf(MemoryEntity(content = "private preference", category = MemoryCategory.PREFERENCE)),
            projects = listOf(project),
            currentProject = project,
            userSettings = UserSettingsEntity(userName = "Private name", primaryInterest = "Private interest"),
            allowCloudRequest = true
        ).toList()

        val received = requireNotNull(provider.receivedRequest)
        assertTrue(received.activeMemories.isEmpty())
        assertNull(received.currentProject)
        assertNull(received.userSettings)
        assertFalse(received.allowPersonalContext)
    }

    @Test
    fun providerCancellationPropagatesWithoutLocalFallback() = runBlocking {
        val provider = FakeProvider(stream = flow { throw CancellationException("test cancellation") })
        val engine = HybridAiEngine(
            context = testContext(),
            cloudEngine = provider,
            onlineStatusOverride = { true }
        )
        val emittedStates = mutableListOf<HybridAiState>()
        var cancellationPropagated = false

        try {
            engine.processHybridRequest(
                prompt = "Please analyze this complex topic in detail",
                mode = HybridRoutingMode.FORCE_CLOUD,
                identity = com.example.data.model.AiIdentityConfig(),
                activeMemories = emptyList(),
                projects = emptyList(),
                allowCloudRequest = true
            ).collect { emittedStates.add(it) }
        } catch (expected: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue("Cancellation must propagate to the caller", cancellationPropagated)
        assertEquals(listOf(HybridAiState.Starting), emittedStates)
    }

    // ---- Routing must never pass off a canned reply or a failure as a normal answer ----

    private fun engine(provider: AiProvider, online: Boolean = true) =
        HybridAiEngine(context = testContext(), cloudEngine = provider, onlineStatusOverride = { online })

    private fun run(engine: HybridAiEngine, mode: HybridRoutingMode, allowCloud: Boolean = true, prompt: String = "Please analyze this complex topic in detail") =
        runBlocking {
            engine.processHybridRequest(
                prompt = prompt,
                mode = mode,
                identity = com.example.data.model.AiIdentityConfig(),
                activeMemories = emptyList(),
                projects = emptyList(),
                allowCloudRequest = allowCloud
            ).toList()
        }

    private fun completed(states: List<HybridAiState>) = states.last() as HybridAiState.Completed

    @Test
    fun cloudOnlyWithCloudChatOffSendsNothingAndSaysWhy() {
        val provider = FakeProvider()
        val result = completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD, allowCloud = false))
        assertNull("nothing may be sent", provider.receivedRequest)
        assertTrue(result.fullText.contains("cloud chat is off"))
        assertEquals(com.example.data.model.AiModelUsed.NONE, result.modelUsed)
    }

    @Test
    fun cloudOnlyOfflineSaysOfflineInsteadOfACannedReply() {
        val provider = FakeProvider()
        val result = completed(run(engine(provider, online = false), HybridRoutingMode.FORCE_CLOUD))
        assertNull(provider.receivedRequest)
        assertTrue(result.fullText.contains("offline"))
    }

    @Test
    fun cloudOnlyProviderErrorShowsTheRealErrorNotAnOfflineReply() {
        val provider = FakeProvider(stream = flow { emit(AiProviderEvent.Error("ServerException: HTTP 403 Firebase AI Logic API has not been used")) })
        val result = completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD))
        assertTrue(result.fullText.contains("Claude couldn't answer"))
        assertTrue(result.fullText.contains("HTTP 403"))
        assertTrue(result.reasoningRationale.contains("HTTP 403"))
        assertEquals("an error is not an answer", com.example.data.model.AiModelUsed.NONE, result.modelUsed)
        assertNull(result.proposedAction)
    }

    @Test
    fun cloudOnlyProviderExceptionIsReportedNotSwallowed() {
        val provider = FakeProvider(stream = flow { throw IllegalStateException("boom") })
        val result = completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD))
        assertTrue(result.fullText.contains("boom"))
    }

    @Test
    fun autoModeFallbackIsLabelledAsAFallback() {
        val provider = FakeProvider(stream = flow { emit(AiProviderEvent.Error("UnknownHostException")) })
        val result = completed(run(engine(provider), HybridRoutingMode.AUTO_HYBRID))
        assertEquals(com.example.data.model.AiModelUsed.LOCAL, result.modelUsed)
        assertTrue(result.fullText.startsWith("\u26a0 Claude is unavailable (UnknownHostException)"))
        assertTrue(result.reasoningRationale.contains("Cloud request failed"))
    }

    @Test
    fun partialAnswerThenFailureIsKeptAndMarkedIncomplete() {
        val provider = FakeProvider(stream = flow {
            emit(AiProviderEvent.Content("First half"))
            emit(AiProviderEvent.Error("Claude stopped responding for 60 seconds; the reply is incomplete."))
        })
        val result = completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD))
        assertEquals(com.example.data.model.AiModelUsed.CLOUD, result.modelUsed)
        assertTrue(result.fullText.startsWith("First half"))
        assertTrue(result.fullText.contains("incomplete"))
    }

    @Test
    fun longAnswersAreNotCutOffByATimer() {
        val provider = FakeProvider(stream = flow {
            emit(AiProviderEvent.Content("a"))
            kotlinx.coroutines.delay(9_000) // longer than the old 8-second whole-answer limit
            emit(AiProviderEvent.Completed("a full answer", 9_000))
        })
        val result = completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD))
        assertEquals("a full answer", result.fullText)
    }

    @Test
    fun greetingRouteUsesWholeWordsOnly() {
        val e = engine(FakeProvider())
        assertTrue(e.shouldRouteToLocal("hi"))
        assertTrue(e.shouldRouteToLocal("hello there"))
        assertFalse(e.shouldRouteToLocal("Explain this theorem"))
        assertFalse(e.shouldRouteToLocal("Which one wins?"))
        assertFalse(e.shouldRouteToLocal("I think so"))
    }

    @Test
    fun answerNamesTheModelThatActuallyAnswered() {
        val provider = FakeProvider(stream = flow {
            emit(AiProviderEvent.Completed("ok", 5, "Claude Test Model"))
        })
        val result = completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD))
        assertEquals(com.example.data.model.AiModelUsed.CLOUD, result.modelUsed)
        assertTrue(result.reasoningRationale.startsWith("Answered by Claude Test Model via your Aura relay"))
    }

    @Test
    fun cloudAnswerWithoutAModelNameIsLabelledClaude() {
        val provider = FakeProvider(stream = flow { emit(AiProviderEvent.Completed("ok", 5)) })
        val result = completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD))
        assertTrue(result.reasoningRationale.startsWith("Answered by Claude via your Aura relay"))
    }


    // ---- What crosses the cloud boundary: name and personality yes, free-text identity no ----

    @Test
    fun cloudRequestKeepsAssistantNameAndPersonalityButNotDirectivesOrValues() {
        val identity = com.example.data.model.AiIdentityConfig(
            name = "  Starai\n ",
            archetype = com.example.data.model.PersonalityArchetype.CREATIVE_STRATEGIST,
            responseLength = com.example.data.model.ResponseLength.values().last(),
            coreValues = "private value",
            customDirectives = "private directive"
        )
        val sent = AiProviderRequest(prompt = "Hi", identity = identity).withoutPersonalContext().identity
        val defaults = com.example.data.model.AiIdentityConfig()

        assertEquals("Starai", sent.name)
        assertEquals(com.example.data.model.PersonalityArchetype.CREATIVE_STRATEGIST, sent.archetype)
        assertEquals(identity.responseLength, sent.responseLength)
        assertEquals(defaults.coreValues, sent.coreValues)
        assertEquals("", sent.customDirectives)
    }

    @Test
    fun cloudNameIsShortenedAndNeverBlank() {
        val long = com.example.data.model.AiIdentityConfig(name = "x".repeat(200))
        assertEquals(40, AiProviderRequest(prompt = "Hi", identity = long).withoutPersonalContext().identity.name.length)

        val blank = com.example.data.model.AiIdentityConfig(name = "   ")
        assertEquals("Aura", AiProviderRequest(prompt = "Hi", identity = blank).withoutPersonalContext().identity.name)
    }

    @Test
    fun historyReachesTheCloudProviderAndIsDescribedInTheRationale() {
        val provider = FakeProvider()
        val history = listOf(
            com.example.ai.ChatTurn(com.example.ai.ChatTurn.Role.USER, "earlier question"),
            com.example.ai.ChatTurn(com.example.ai.ChatTurn.Role.ASSISTANT, "earlier answer")
        )
        val result = runBlocking {
            engine(provider).processHybridRequest(
                prompt = "Please analyze this complex topic in detail",
                mode = HybridRoutingMode.FORCE_CLOUD,
                identity = com.example.data.model.AiIdentityConfig(),
                activeMemories = emptyList(),
                projects = emptyList(),
                allowCloudRequest = true,
                history = history
            ).toList()
        }
        assertEquals(history, requireNotNull(provider.receivedRequest).history)
        assertTrue(completed(result).reasoningRationale.contains("last 1 earlier Claude exchange in this chat"))
    }

    @Test
    fun cloudPromptSaysWhatClaudeCannotSee() {
        val identity = com.example.data.model.AiIdentityConfig()
        val prompt = com.example.ai.AuraPrompt.system(
            AiProviderRequest(prompt = "Hi", identity = identity).withoutPersonalContext().identity
        )
        assertTrue(prompt.contains("no access to the user's saved memories, goals, projects or profile"))
        assertFalse("must not claim to track goals", prompt.contains("active goals and project constraints"))
    }

    @Test
    fun cloudPromptSaysClaudeCannotTakeActions() {
        val prompt = com.example.ai.AuraPrompt.system(com.example.data.model.AiIdentityConfig())
        assertTrue(prompt.contains("You cannot take actions or change anything in the app"))
        assertTrue(prompt.contains("never list \"proposed actions for approval\""))
        assertFalse(prompt.contains("Actions only happen after the user approves them in the app"))
    }

    @Test
    fun cloudRepliesNeverCarryKeywordActionCards() {
        // These prompts make the on-phone keyword engine propose actions; a Claude reply must not.
        for (prompt in listOf("remember that I like tea", "create project Garden", "add task: buy milk")) {
            val result = completed(run(engine(FakeProvider()), HybridRoutingMode.FORCE_CLOUD, prompt = prompt))
            assertEquals(com.example.data.model.AiModelUsed.CLOUD, result.modelUsed)
            assertNull("no approval card under a Claude reply to \"$prompt\"", result.proposedAction)
        }
    }

    // ---- Personal details: only with the sharing switch, only what the user marked ----

    private fun mem(id: Long, text: String, share: Boolean, active: Boolean = true) = com.example.data.model.MemoryEntity(
        id = id, content = text, category = com.example.data.model.MemoryCategory.FACT, isActive = active, shareWithCloud = share
    )

    private val personalRequest = AiProviderRequest(
        prompt = "Hi",
        identity = com.example.data.model.AiIdentityConfig(customDirectives = "private directive"),
        activeMemories = listOf(mem(1, "shared fact", true), mem(2, "private fact", false), mem(3, "switched off", true, active = false)),
        currentProject = com.example.data.model.ProjectEntity(title = "Secret project", description = "x"),
        userSettings = com.example.data.model.UserSettingsEntity(id = 1, userName = "Ty", primaryInterest = "Robots")
    )

    @Test
    fun withoutTheSwitchNothingPersonalIsSent() {
        val sent = personalRequest.forCloud(sharePersonalContext = false)
        assertTrue(sent.activeMemories.isEmpty())
        assertNull(sent.userSettings)
        assertNull(sent.currentProject)
        assertFalse(sent.allowPersonalContext)
    }

    @Test
    fun withTheSwitchOnlyMarkedActiveMemoriesAndProfileAreSent() {
        val sent = personalRequest.forCloud(sharePersonalContext = true)
        assertEquals(listOf("shared fact"), sent.activeMemories.map { it.content })
        assertEquals("Ty", sent.userSettings?.userName)
        assertNull("projects never go", sent.currentProject)
        assertEquals("", sent.identity.customDirectives)
        assertTrue(sent.allowPersonalContext)
    }

    @Test
    fun sharedMemoriesAreCappedAndEmptyProfileIsNotSent() {
        val many = personalRequest.copy(
            activeMemories = (1L..20L).map { mem(it, "m$it", true) },
            userSettings = com.example.data.model.UserSettingsEntity(id = 1)
        ).forCloud(sharePersonalContext = true)
        assertEquals(12, many.activeMemories.size)
        assertNull(many.userSettings)
    }

    @Test
    fun routerSendsSharedDetailsAndSaysSo() {
        val provider = FakeProvider()
        val result = runBlocking {
            engine(provider).processHybridRequest(
                prompt = "Please analyze this complex topic in detail",
                mode = HybridRoutingMode.FORCE_CLOUD,
                identity = com.example.data.model.AiIdentityConfig(),
                activeMemories = personalRequest.activeMemories,
                projects = emptyList(),
                userSettings = personalRequest.userSettings,
                allowCloudRequest = true,
                sharePersonalContext = true
            ).toList()
        }
        assertEquals(listOf("shared fact"), requireNotNull(provider.receivedRequest).activeMemories.map { it.content })
        val rationale = completed(result).reasoningRationale
        assertTrue(rationale, rationale.contains("1 memory you marked to share"))
        assertTrue(rationale, rationale.contains("your name and main interest"))
    }

    @Test
    fun promptExplainsWhatClaudeCanSeeWhenDetailsAreShared() {
        val sent = personalRequest.forCloud(sharePersonalContext = true)
        val prompt = com.example.ai.AuraPrompt.system(sent.identity, sent.activeMemories, sent.currentProject, sent.userSettings)
        assertTrue(prompt.contains("only the personal details listed below"))
        assertTrue(prompt.contains("shared fact"))
        assertFalse(prompt.contains("private fact"))
        assertFalse(prompt.contains("Secret project"))
    }

    @Test
    fun proposalInstructionsNeverAllowClaimingSuccess() {
        val prompt = com.example.ai.AuraPrompt.system(com.example.data.model.AiIdentityConfig(), proposalsEnabled = true)
        assertTrue(prompt.contains("You can PROPOSE exactly four changes"))
        assertTrue(prompt.contains("never say anything was saved, created, scheduled or done"))
        assertFalse(prompt.contains("You cannot take actions or change anything in the app"))
    }

    // ---- Open to-dos: only with their own switch, titles and dates only ----

    @Test
    fun onlyOpenTodosGoTitlesAndDatesSoonestFirstAtMostThirty() {
        val t = { id: Long, title: String, due: Long?, done: Boolean, deleted: Long? ->
            com.example.data.model.TodoEntity(id = id, title = title, notes = "secret note", dueEpochDay = due, projectId = 9, done = done, deletedAt = deleted, createdAt = id)
        }
        val shared = com.example.ai.CloudTodos.from(listOf(
            t(1, "later", 20, false, null), t(2, "soon", 10, false, null), t(3, "undated", null, false, null),
            t(4, "finished", 5, true, null), t(5, "binned", 5, false, 99)
        ))
        assertEquals(listOf("soon", "later", "undated"), shared.map { it.title })
        assertEquals("1970-01-11", shared[0].dueDate)
        assertNull(shared[2].dueDate)
        val many = com.example.ai.CloudTodos.from((1L..40L).map { t(it, "t$it", null, false, null) })
        assertEquals(30, many.size)
    }

    @Test
    fun routerSendsSharedTodosAndSaysSo() {
        val provider = FakeProvider()
        val todos = listOf(com.example.ai.CloudTodo("Walk dogs", "2026-11-09"), com.example.ai.CloudTodo("Buy soil", null))
        val result = runBlocking {
            engine(provider).processHybridRequest(
                prompt = "Please analyze this complex topic in detail",
                mode = HybridRoutingMode.FORCE_CLOUD,
                identity = com.example.data.model.AiIdentityConfig(),
                activeMemories = emptyList(),
                projects = emptyList(),
                allowCloudRequest = true,
                sharedTodos = todos
            ).toList()
        }
        assertEquals(todos, requireNotNull(provider.receivedRequest).openTodos)
        assertTrue(completed(result).reasoningRationale.contains("2 open to-dos (titles and due dates)"))
    }

    @Test
    fun noTodosAreSentUnlessShared() {
        val provider = FakeProvider()
        completed(run(engine(provider), HybridRoutingMode.FORCE_CLOUD))
        assertTrue(requireNotNull(provider.receivedRequest).openTodos.isEmpty())
    }

    @Test
    fun promptListsSharedTodosAsData() {
        val prompt = com.example.ai.AuraPrompt.system(
            com.example.data.model.AiIdentityConfig(), proposalsEnabled = true,
            openTodos = listOf(com.example.ai.CloudTodo("Walk dogs", "2026-11-09"))
        )
        assertTrue(prompt.contains("[USER'S OPEN TO-DOS"))
        assertTrue(prompt.contains("- Walk dogs (due 2026-11-09)"))
        assertTrue(prompt.contains("cannot see the user's projects, finished to-dos, or to-do notes"))
        assertFalse(prompt.contains("You can see only this conversation"))
        val without = com.example.ai.AuraPrompt.system(com.example.data.model.AiIdentityConfig(), proposalsEnabled = true)
        assertFalse(without.contains("OPEN TO-DOS"))
        assertTrue(without.contains("existing to-dos or projects"))
    }
}
