package com.example

import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderContractTest {

    private class FakeProvider(
        private val stream: Flow<AiProviderEvent> = flow {
            emit(AiProviderEvent.Content("response"))
            emit(AiProviderEvent.Completed("response", 1L))
        }
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
            return stream
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
            userSettings = UserSettingsEntity(userName = "Private name", primaryInterest = "Private interest")
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
                projects = emptyList()
            ).collect { emittedStates.add(it) }
        } catch (expected: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue("Cancellation must propagate to the caller", cancellationPropagated)
        assertEquals(listOf(HybridAiState.Starting), emittedStates)
    }

}
