package com.example

import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.withoutPersonalContext
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.UserSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderContractTest {

    private class FakeProvider : AiProvider {
        override val providerId = "test-provider"
        override val capabilities = AiProviderCapabilities(
            streaming = true,
            supportsPersonalContext = true,
            supportsOffline = false
        )

        override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> = flow {
            emit(AiProviderEvent.Content(request.prompt))
            emit(AiProviderEvent.Completed(request.prompt, 1L))
        }
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
}
