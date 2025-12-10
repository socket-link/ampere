package link.socket.ampere.agents.events.messages.escalation

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.escalation.ConsoleNotifier
import link.socket.ampere.agents.events.messages.MessageThreadId

class ConsoleNotifierTest {

    @Test
    fun `console notifier prints expected content`() {
        runBlocking {
            val notifier = ConsoleNotifier()
            val threadId: MessageThreadId = "thread-123"
            val agentId: AgentId = "agent-xyz"
            val reason = "Manual review required"
            val context = mapOf("ticket" to "ABC-42", "priority" to "high")

            val originalOut = System.out
            val output = ByteArrayOutputStream()
            System.setOut(PrintStream(output))
            try {
                notifier.notifyEscalation(threadId, agentId, reason, context)
            } finally {
                System.setOut(originalOut)
            }

            val printed = output.toString()

            assertTrue(printed.contains("ESCALATION REQUIRED"))
            assertTrue(printed.contains("Conversation ID: $threadId"))
            assertTrue(printed.contains("Requesting Agent: $agentId"))
            assertTrue(printed.contains("Reason: $reason"))

            // Context lines may be in any order due to map iteration; check both keys present
            assertTrue(printed.contains("ticket: ${context["ticket"]}"))
            assertTrue(printed.contains("priority: ${context["priority"]}"))
        }
    }
}
