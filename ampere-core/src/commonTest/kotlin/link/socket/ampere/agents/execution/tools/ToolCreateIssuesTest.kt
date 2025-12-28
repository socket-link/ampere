package link.socket.ampere.agents.execution.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import link.socket.ampere.agents.domain.config.AgentActionAutonomy

class ToolCreateIssuesTest {

    @Test
    fun `ToolCreateIssues factory creates tool with correct metadata`() {
        val tool = ToolCreateIssues()

        assertEquals("create_issues", tool.id)
        assertEquals("Create Issues", tool.name)
        assertEquals(AgentActionAutonomy.ACT_WITH_NOTIFICATION, tool.requiredAgentAutonomy)
        assert(tool.description.contains("hierarchical"))
        assert(tool.description.contains("dependency"))
    }

    @Test
    fun `ToolCreateIssues factory accepts custom autonomy level`() {
        val tool = ToolCreateIssues(
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
        )

        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, tool.requiredAgentAutonomy)
    }

    @Test
    fun `ToolCreateIssues is a FunctionTool`() {
        val tool = ToolCreateIssues()

        assert(tool is FunctionTool)
    }
}
