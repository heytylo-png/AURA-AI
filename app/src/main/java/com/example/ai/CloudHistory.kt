package com.example.ai

import com.example.data.model.ActionStatus
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.Sender
import com.example.data.model.TaskActionEntity

/** One turn of a conversation sent to a cloud model. */
data class ChatTurn(val role: Role, val text: String) {
    enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }
}

/**
 * Chooses which earlier messages of a conversation may go to the cloud with a new message.
 *
 * Only exchanges that already went to the cloud are re-sent: a user message is included only when
 * the reply right after it was a cloud answer. Messages answered on the phone, notices
 * ("cloud chat is off") and failed requests stay on the phone, so turning cloud chat on never
 * uploads anything that was kept local before.
 */
object CloudHistory {
    /** Most recent exchanges to keep (each is one user + one assistant turn). */
    const val MAX_EXCHANGES = 10

    /** Character budget for the history; the relay accepts 100,000 characters in total. */
    const val MAX_CHARS = 40_000

    fun fromMessages(
        messages: List<ChatMessageEntity>,
        maxExchanges: Int = MAX_EXCHANGES,
        maxChars: Int = MAX_CHARS,
        /** Actions linked to replies (by id), so Claude learns what happened to its proposals. */
        actions: Map<Long, TaskActionEntity> = emptyMap(),
        /** Only messages from this time on (epoch ms): set when the user turns a sharing switch off. */
        since: Long = 0L
    ): List<ChatTurn> {
        val exchanges = mutableListOf<Pair<String, String>>()
        for (i in 0 until messages.size - 1) {
            val user = messages[i]
            val reply = messages[i + 1]
            if (user.timestamp < since || reply.timestamp < since) continue
            if (user.sender == Sender.USER && user.text.isNotBlank() &&
                reply.sender == Sender.AI && reply.modelUsed == AiModelUsed.CLOUD && reply.text.isNotBlank()
            ) {
                val note = reply.proposedActionId?.let { actions[it] }?.let { outcomeNote(it) }
                exchanges += user.text to (if (note == null) reply.text else "${reply.text}\n\n$note")
            }
        }

        // Keep the newest exchanges that fit both limits, then restore chronological order.
        val kept = ArrayDeque<Pair<String, String>>()
        var chars = 0
        for (exchange in exchanges.asReversed()) {
            val size = exchange.first.length + exchange.second.length
            if (kept.size >= maxExchanges || chars + size > maxChars) break
            kept.addFirst(exchange)
            chars += size
        }
        return kept.flatMap { (user, assistant) ->
            listOf(ChatTurn(ChatTurn.Role.USER, user), ChatTurn(ChatTurn.Role.ASSISTANT, assistant))
        }
    }

    /** What happened to a proposal, told to Claude so it never assumes a change was made. */
    fun outcomeNote(action: TaskActionEntity): String {
        val what = "${action.title.lowercase()} \"${action.description.take(80)}\""
        val outcome = when (action.status) {
            ActionStatus.PROPOSED -> "the user hasn't decided yet"
            ActionStatus.EXECUTING -> "it is being saved"
            ActionStatus.APPROVED_EXECUTED -> "the user approved it and it was saved"
            ActionStatus.FAILED -> "the user approved it but saving failed, so nothing was saved"
            ActionStatus.DISMISSED -> "the user dismissed it; nothing was changed"
        }
        return "[Aura: you proposed to $what; $outcome.]"
    }
}
