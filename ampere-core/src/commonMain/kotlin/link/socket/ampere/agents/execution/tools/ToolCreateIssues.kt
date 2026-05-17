package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext

/**
 * Platform-specific implementation for creating issues in a project management system.
 * This expect function must be implemented for each platform (JVM, Android, iOS).
 */
expect suspend fun executeCreateIssues(
    context: ExecutionContext.IssueManagement,
): ExecutionOutcome.IssueManagement

/**
 * Creates a FunctionTool that creates issues in a project management system.
 *
 * Supports hierarchical issue creation (epics containing tasks) and dependency
 * tracking between tasks. Issues are created in batch with parent-child relationships
 * and dependencies documented.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to ACT_WITH_NOTIFICATION since creating issues is reversible
 *        (can be closed/deleted) but should notify stakeholders.
 * @return A FunctionTool configured to create issues.
 */
const val CREATE_ISSUES_TOOL_ID: String = "create_issues"

fun ToolCreateIssues(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
    parameterStrategy: ParameterStrategy? = null,
): FunctionTool<ExecutionContext.IssueManagement> {
    return FunctionTool(
        id = CREATE_ISSUES_TOOL_ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            executeCreateIssues(executionRequest.context)
        },
        parameterStrategy = parameterStrategy,
    )
}

private const val ID = "create_issues"
private const val NAME = "Create Issues"
private const val DESCRIPTION = """
Creates one or more issues in a project management system.
Supports hierarchical creation (epics containing tasks) and
dependency tracking between tasks.
"""
