package com.example

import com.example.ai.ChatTurn
import com.example.ai.CloudHistory
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only exchanges that already went to the cloud may be sent again as history. */
class CloudHistoryTest {

    private fun user(text: String) = ChatMessageEntity(sender = Sender.USER, text = text)
    private fun ai(text: String, model: AiModelUsed?) = ChatMessageEntity(sender = Sender.AI, text = text, modelUsed = model)
    private fun u(text: String) = ChatTurn(ChatTurn.Role.USER, text)
    private fun a(text: String) = ChatTurn(ChatTurn.Role.ASSISTANT, text)

    @Test
    fun keepsCloudExchangesInOrder() {
        val history = CloudHistory.fromMessages(
            listOf(user("q1"), ai("a1", AiModelUsed.CLOUD), user("q2"), ai("a2", AiModelUsed.CLOUD))
        )
        assertEquals(listOf(u("q1"), a("a1"), u("q2"), a("a2")), history)
    }

    @Test
    fun leavesOutMessagesThatNeverWentToTheCloud() {
        val history = CloudHistory.fromMessages(
            listOf(
                user("remember my secret"), ai("Saved locally", AiModelUsed.LOCAL),
                user("cloud is off"), ai("Nothing was sent", AiModelUsed.NONE),
                user("old message"), ai("legacy reply", null),
                user("unanswered"),
                user("q"), ai("a", AiModelUsed.CLOUD)
            )
        )
        assertEquals(listOf(u("q"), a("a")), history)
    }

    @Test
    fun keepsOnlyTheNewestExchanges() {
        val messages = (1..15).flatMap { listOf(user("q$it"), ai("a$it", AiModelUsed.CLOUD)) }
        val history = CloudHistory.fromMessages(messages, maxExchanges = 3)
        assertEquals(listOf(u("q13"), a("a13"), u("q14"), a("a14"), u("q15"), a("a15")), history)
    }

    @Test
    fun staysWithinTheCharacterBudget() {
        val messages = listOf(
            user("x".repeat(50)), ai("y".repeat(50), AiModelUsed.CLOUD),
            user("q"), ai("a", AiModelUsed.CLOUD)
        )
        assertEquals(listOf(u("q"), a("a")), CloudHistory.fromMessages(messages, maxChars = 60))
        assertTrue(CloudHistory.fromMessages(messages, maxChars = 1).isEmpty())
    }

    @Test
    fun emptyChatHasNoHistory() {
        assertTrue(CloudHistory.fromMessages(emptyList()).isEmpty())
    }
}
