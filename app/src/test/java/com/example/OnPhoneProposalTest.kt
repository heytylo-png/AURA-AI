package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Replies made on the phone keep proposing actions (only Claude's replies stopped). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnPhoneProposalTest {

    private val unusedCloud = object : AiProvider {
        override val providerId = "unused"
        override val capabilities = AiProviderCapabilities(streaming = true, supportsPersonalContext = true, supportsOffline = false)
        override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> = emptyFlow()
    }

    @Test
    fun localModeStillProposesSavingAMemory() = runBlocking {
        val engine = HybridAiEngine(ApplicationProvider.getApplicationContext(), cloudEngine = unusedCloud, onlineStatusOverride = { true })
        val result = engine.processHybridRequest(
            prompt = "remember that I like tea",
            mode = HybridRoutingMode.FORCE_LOCAL,
            identity = AiIdentityConfig(),
            activeMemories = emptyList(),
            projects = emptyList(),
            allowCloudRequest = true
        ).toList().last() as HybridAiState.Completed

        assertEquals(AiModelUsed.LOCAL, result.modelUsed)
        assertNotNull(result.proposedAction)
    }
}
