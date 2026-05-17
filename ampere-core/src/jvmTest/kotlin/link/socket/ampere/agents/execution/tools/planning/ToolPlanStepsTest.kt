package link.socket.ampere.agents.execution.tools.planning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

/**
 * Verifies that the agent-neutral `plan_steps` tool owns its planning prompt
 * and JSON schema end-to-end — neither belongs on any per-agent profile.
 */
class ToolPlanStepsTest {

    private fun planningContext(
        intent: String = "implement a fizzbuzz function",
        tools: List<ExecutionContext.ToolDescriptor> = listOf(
            ExecutionContext.ToolDescriptor(
                id = "write_code_file",
                name = "Write Code File",
                description = "Writes a code file in the current workspace.",
            ),
            ExecutionContext.ToolDescriptor(
                id = "git_commit",
                name = "Commit Changes",
                description = "Commits staged changes.",
            ),
        ),
    ): ExecutionContext.Planning {
        val ticket = Ticket(
            id = "ticket-1",
            title = "Ship fizzbuzz",
            description = intent,
            type = TicketType.TASK,
            priority = TicketPriority.LOW,
            status = TicketStatus.Ready,
            assignedAgentId = null,
            createdByAgentId = "test-agent",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = intent,
        )
        return ExecutionContext.Planning(
            executorId = "test-agent",
            ticket = ticket,
            task = task,
            instructions = intent,
            agentRole = "Code Writer",
            ideaSummary = "Existing fizzbuzz tests are red.",
            knowledgeSummary = "Past: tests-first approach worked.",
            availableToolDescriptors = tools,
        )
    }

    @Test
    fun `tool factory wires the strategy and exposes the canonical id`() {
        val tool = ToolPlanSteps()
        assertEquals(PLAN_STEPS_TOOL_ID, tool.id)
        assertEquals(AgentActionAutonomy.FULLY_AUTONOMOUS, tool.requiredAgentAutonomy)
        assertNotNull(tool.parameterStrategy)
        assertTrue(tool.parameterStrategy is PlanStepsStrategy)
    }

    @Test
    fun `buildPrompt embeds the JSON schema and available tool ids`() {
        val tool = ToolPlanSteps()
        val strategy = tool.parameterStrategy as PlanStepsStrategy
        val context = planningContext()
        val request = ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(),
        )

        val prompt = strategy.buildPrompt(tool, request, intent = context.instructions)

        assertTrue(prompt.contains("\"steps\""), "prompt should declare the steps array")
        assertTrue(prompt.contains("\"toolToUse\""), "prompt should call out toolToUse")
        assertTrue(prompt.contains("\"estimatedComplexity\""), "prompt should call out estimatedComplexity")
        assertTrue(prompt.contains("write_code_file"), "prompt should surface the available tool ids")
        assertTrue(
            prompt.contains("autonomous Code Writer agent"),
            "prompt should personalise to the agent role from the context",
        )
        assertTrue(
            prompt.contains("implement a fizzbuzz function"),
            "prompt should include the task intent",
        )
    }

    @Test
    fun `parseAndEnrichRequest builds a Plan from a well-formed JSON response`() {
        val tool = ToolPlanSteps()
        val strategy = tool.parameterStrategy as PlanStepsStrategy
        val context = planningContext()
        val request = ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(),
        )

        val response = """
            {
              "steps": [
                {"description": "write fizzbuzz function", "toolToUse": "write_code_file", "requiresPreviousStep": false},
                {"description": "commit changes", "toolToUse": "git_commit", "requiresPreviousStep": true}
              ],
              "estimatedComplexity": 3
            }
        """.trimIndent()

        val enriched = strategy.parseAndEnrichRequest(response, request)
        val enrichedContext = enriched.context as ExecutionContext.Planning
        val plan = enrichedContext.parsedPlan
        assertNotNull(plan)
        assertTrue(plan is Plan.ForTask)
        assertEquals(2, plan.tasks.size)
        assertEquals(3, plan.estimatedComplexity)
    }

    @Test
    fun `execute returns a planning Success when the strategy populated parsedPlan`() = runTest {
        val tool = ToolPlanSteps()
        val context = planningContext()

        val strategy = tool.parameterStrategy as PlanStepsStrategy
        val response = """
            {
              "steps": [{"description": "do the thing", "toolToUse": null, "requiresPreviousStep": false}],
              "estimatedComplexity": 1
            }
        """.trimIndent()
        val enriched = strategy.parseAndEnrichRequest(
            response,
            ExecutionRequest(context = context, constraints = ExecutionConstraints()),
        )

        @Suppress("UNCHECKED_CAST")
        val typedEnriched = enriched as ExecutionRequest<ExecutionContext.Planning>

        val outcome = tool.execute(typedEnriched)
        assertTrue(outcome is ExecutionOutcome.Planning.Success)
        assertEquals(1, outcome.plan.tasks.size)
    }

    @Test
    fun `execute fails fast when the strategy did not populate parsedPlan`() = runTest {
        val tool = ToolPlanSteps()
        val request = ExecutionRequest(
            context = planningContext(),
            constraints = ExecutionConstraints(),
        )

        val outcome = tool.execute(request)
        assertTrue(outcome is ExecutionOutcome.Planning.Failure)
    }
}
