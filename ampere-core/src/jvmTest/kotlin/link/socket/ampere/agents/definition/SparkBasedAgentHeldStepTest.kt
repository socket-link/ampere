package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool

/**
 * AMPR-425: a tool that reports its action is waiting on a person ends its step Held.
 *
 * `executePlanStep` mapped a tool's success to a success, its failure to a critical
 * failure, and *anything else* to a success — so a hold read as done, and the plan
 * walked on with a result that did not exist. The Arc path's walk
 * (`AutonomousAgent.executePlan`) hands each step to `PlanExecutor` as its own one-step
 * plan, so it has to stop on a hold itself; the second case pins that it does.
 *
 * `runBlocking` rather than `runTest`: the agent's phase lambdas hop to the IO
 * dispatcher inside `withTimeout`, which a virtual clock walks straight past.
 */
class SparkBasedAgentHeldStepTest {

    private val phaseSparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

    @Test
    fun `a tool that returns Held ends its step Held, not succeeded and not failed`() {
        val tool = HoldingTool(id = "git_push")
        val agent = agentWith(tool)
        val step = step("step-1-parent", "push the branch", toolId = tool.id)

        val result = runBlocking {
            agent.executePlanStep(step = step, parentTask = parent(), priorResults = emptyList())
        }

        val held = assertIs<StepResult.Held>(result)
        assertEquals("push the branch", held.description)
        assertEquals(REASON, held.reason)
        assertEquals(REFERENCE, held.reference)
        assertEquals(1, tool.invocations.size)
    }

    @Test
    fun `the Arc walk stops at a held step and dispatches nothing after it`() {
        val holding = HoldingTool(id = "git_push")
        val after = HoldingTool(id = "git_create_pr")
        val agent = agentWith(holding, after)
        val plan = Plan.ForTask(
            task = parent(),
            tasks = listOf(
                step("step-1-parent", "push the branch", toolId = holding.id),
                step("step-2-parent", "open the pull request", toolId = after.id),
            ),
            estimatedComplexity = 2,
        )

        val outcome = runBlocking { agent.executePlan(plan) }

        assertEquals(1, holding.invocations.size)
        assertEquals(0, after.invocations.size, "the step after a hold is never dispatched")
        val held = assertIs<ExecutionOutcome.Held>(outcome)
        assertEquals(REASON, held.reason)
        assertEquals(REFERENCE, held.reference)
        assertFalse(outcome is Outcome.Success)
        assertFalse(outcome is Outcome.Failure)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /**
     * A tool whose action waits on a person: it records each invocation and holds. Named
     * with ids the Code role's spark allows, because dispatch is against the narrowed set.
     */
    private class HoldingTool(val id: String) {
        val invocations: MutableList<ExecutionRequest<*>> = CopyOnWriteArrayList()

        val tool: FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
            id = id,
            name = "Holding $id",
            description = "waits on a person",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                invocations += request
                val now = Clock.System.now()
                ExecutionOutcome.Held(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = now,
                    reason = REASON,
                    reference = REFERENCE,
                )
            },
        )
    }

    private fun parent(): Task.Step = Task.Step(
        id = "parent",
        status = TaskStatus.Pending,
        description = "ship the fix",
    )

    private fun step(id: String, description: String, toolId: String): Task.Step = Task.Step(
        id = id,
        status = TaskStatus.Pending,
        description = description,
        toolId = toolId,
    )

    private fun agentWith(vararg tools: HoldingTool): SparkBasedAgent<CodeState> {
        val byId = tools.associateBy { it.id }
        val reasoning = AgentReasoning.createForTesting(executorId = "held-step-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { tool, request ->
                val holding = byId.getValue(tool.id)

                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { holding.tool.execute(typed) } as ExecutionOutcome
            }
        }
        return SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "held-step-agent",
            tools = tools.map { it.tool }.toSet<Tool<*>>(),
            reasoningOverride = reasoning,
        )
    }

    private companion object {
        const val REASON = "waiting for the repository owner to approve the push"
        const val REFERENCE = "approval-request-17"
    }
}
