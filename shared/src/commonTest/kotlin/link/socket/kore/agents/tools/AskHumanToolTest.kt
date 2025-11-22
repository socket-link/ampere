package link.socket.kore.agents.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import link.socket.kore.agents.core.Outcome
import link.socket.kore.agents.events.tasks.CodeChange

class AskHumanToolTest {

    private val stubSourceTask = CodeChange("source", "")

    @Test
    fun `validateParameters returns true when question is present and a String`() {
        val tool = AskHumanTool { "ok" }
        val params = mapOf("question" to "What should I do?")
        assertEquals(true, tool.validateParameters(params))
    }

    @Test
    fun `validateParameters returns false when question missing or wrong type`() {
        val tool = AskHumanTool { "ok" }
        assertEquals(false, tool.validateParameters(emptyMap()))
        assertEquals(false, tool.validateParameters(mapOf("question" to 123)))
    }

    @Test
    fun `execute returns success with human response`() = runBlocking {
        val tool = AskHumanTool { q -> "Response to: $q" }
        val result = tool.execute(stubSourceTask, mapOf("question" to "Proceed?"))
        assertIs<Outcome.Success.Full>(result)
        assertEquals("Response to: Proceed?", result.value)
    }

    @Test
    fun `execute returns failure when question missing`() = runBlocking {
        val tool = AskHumanTool { "never called" }
        val result = tool.execute(stubSourceTask, emptyMap())
        assertIs<Outcome.Failure>(result)
        assertEquals("Missing 'question' parameter", result.errorMessage)
    }

    @Test
    fun `execute returns failure when humanInterface throws`() = runBlocking {
        val tool = AskHumanTool { throw IllegalStateException("no human available") }
        val result = tool.execute(stubSourceTask, mapOf("question" to "Hello?"))
        assertIs<Outcome.Failure>(result)
        assertEquals(true, result.errorMessage.contains("Failed to get human response"))
    }
}
