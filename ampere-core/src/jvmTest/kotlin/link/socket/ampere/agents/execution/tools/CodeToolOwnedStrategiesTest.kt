package link.socket.ampere.agents.execution.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeParams

/**
 * Confirms the AMPR-163 Task 4 contract: code tools ship with their own
 * [link.socket.ampere.agents.execution.ParameterStrategy] so the
 * `ToolExecutionEngine`'s tool-owned path resolves the strategy without an
 * agent-side `registerStrategy(...)` call.
 */
class CodeToolOwnedStrategiesTest {

    @Test
    fun `ToolWriteCodeFile carries CodeParams CodeWriting by default`() {
        val tool = ToolWriteCodeFile(AgentActionAutonomy.ACT_WITH_NOTIFICATION)
        assertEquals(WRITE_CODE_FILE_TOOL_ID, tool.id)
        val strategy = assertNotNull(tool.parameterStrategy)
        assertTrue(
            strategy is CodeParams.CodeWriting,
            "default strategy should be CodeParams.CodeWriting, got ${strategy::class.simpleName}",
        )
    }

    @Test
    fun `ToolReadCodeFile carries CodeParams CodeReading by default`() {
        val tool = ToolReadCodeFile()
        assertEquals(READ_CODE_FILE_TOOL_ID, tool.id)
        val strategy = assertNotNull(tool.parameterStrategy)
        assertTrue(
            strategy is CodeParams.CodeReading,
            "default strategy should be CodeParams.CodeReading, got ${strategy::class.simpleName}",
        )
    }

    @Test
    fun `callers can opt out of the default parameter strategy`() {
        val noStrategy = ToolWriteCodeFile(
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
            parameterStrategy = null,
        )
        assertEquals(null, noStrategy.parameterStrategy)
    }
}
