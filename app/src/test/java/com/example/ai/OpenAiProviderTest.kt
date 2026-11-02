package com.example.ai

import com.example.data.model.AiIdentityConfig
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {

    @Test
    fun providerDeclaresNoPersonalContextCapability() {
        val provider = OpenAiProvider("https://aura.example.com")
        assertEquals("openai", provider.providerId)
        assertTrue(provider.capabilities.streaming)
        assertFalse(provider.capabilities.supportsPersonalContext)
        assertFalse(provider.capabilities.supportsOffline)
    }

    @Test
    fun rejectsPersonalContextBeforeMakingNetworkRequest() = runBlocking {
        val provider = OpenAiProvider("https://aura.example.com")
        val events = provider.generateStream(
            AiProviderRequest(
                prompt = "Summarize my project",
                identity = AiIdentityConfig(),
                activeMemories = listOf(
                    MemoryEntity(content = "private detail", category = MemoryCategory.PREFERENCE)
                )
            )
        ).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AiProviderEvent.Error)
        assertTrue((events.single() as AiProviderEvent.Error).message.contains("cannot include personal context"))
    }

    @Test
    fun identityPromptCarriesAuraIdentityWithoutPersonalData() {
        val provider = OpenAiProvider("https://aura.example.com")
        val prompt = provider.identityPrompt(AiIdentityConfig(name = "Aura Test"))

        assertTrue(prompt.contains("Aura Test"))
        assertTrue(prompt.contains("Personality:"))
        assertTrue(prompt.contains("Core values:"))
        assertTrue(prompt.contains("approve before execution"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesInsecureHttpBackend() {
        OpenAiProvider("http://aura.example.com")
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesBackendUrlContainingCredentials() {
        OpenAiProvider("https://user:password@aura.example.com")
    }
}
