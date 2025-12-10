package link.socket.ampere.agents.events.escalation

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.messages.MessageThreadId

interface Notifier {

    abstract class Agent : Notifier {

        abstract suspend fun notifyEscalation(
            threadId: MessageThreadId,
            reason: String,
            context: Map<String, String>? = null,
        )
    }

    abstract class Human : Notifier {

        abstract suspend fun notifyEscalation(
            threadId: MessageThreadId,
            agentId: AgentId,
            reason: String,
            context: Map<String, String>? = null,
        )
    }
}

class ConsoleNotifier : Notifier.Human() {

    override suspend fun notifyEscalation(
        threadId: MessageThreadId,
        agentId: AgentId,
        reason: String,
        context: Map<String, String>?,
    ) {
        println(
            (
                """
            ════════════════════════════════════════
            🚨 ESCALATION REQUIRED 🚨
            ════════════════════════════════════════
            Conversation ID: $threadId
            Requesting Agent: $agentId
            Reason: $reason
            Context: ${context?.entries?.joinToString("\n    ") { "${it.key}: ${it.value}" }}
            ════════════════════════════════════════
                """.trimIndent()
                ),
        )
    }
}
