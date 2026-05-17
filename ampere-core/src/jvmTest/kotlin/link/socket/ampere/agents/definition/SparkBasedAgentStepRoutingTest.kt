package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.expectation.Expectations
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Exercises the AMPR-163 Task 5 routing contract on
 * [SparkBasedAgent.runLLMToExecuteTask]: plan steps dispatch strictly by
 * `Task.CodeChange.toolId` into the agent's `requiredTools`, with no
 * keyword-routing fallback when the tool id is missing or unknown.
 *
 * The test wires a mock reasoning instance so it can produce arbitrary
 * plans and observe tool invocations without standing up a real LLM or
 * git workspace.
 */
class SparkBasedAgentStepRoutingTest {

    /** A recording tool that captures every invocation for later assertion. */
    private class RecordingTool(val id: String) {
        val invocations: MutableList<ExecutionRequest<*>> = CopyOnWriteArrayList()

        val tool: FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
            id = id,
            name = "Recording $id",
            description = "test recording tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                invocations += request
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "recorded by $id",
                )
            },
        )
    }

    private fun planStep(id: String, description: String, toolId: String?): Task.CodeChange =
        Task.CodeChange(
            id = id,
            status = TaskStatus.Pending,
            description = description,
            toolId = toolId,
        )

    private fun parentTask(description: String = "do the work"): Task.CodeChange =
        Task.CodeChange(
            id = "parent-task",
            status = TaskStatus.Pending,
            description = description,
        )

    @Test
    fun `plan step nominating an available tool invokes that tool exactly once`() {
        val recorder = RecordingTool(id = "git_commit")
        val plan = Plan.ForTask(
            task = parentTask(),
            tasks = listOf(planStep("step-1-parent-task", "commit changes", toolId = "git_commit")),
            estimatedComplexity = 1,
            expectations = Expectations.blank,
        )
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> plan }
            onToolExecution { _, request ->
                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { recorder.tool.execute(typed) } as ExecutionOutcome
            }
        }
        val agent = SparkBasedAgent.Code(
            agentId = "routing-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(parentTask())

        assertEquals(1, recorder.invocations.size, "the nominated tool should be invoked exactly once")
        assertTrue(
            outcome is Outcome.Success,
            "the run should succeed when the tool succeeded; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `plan step nominating an unknown toolId fails fast with a clear error`() {
        val recorder = RecordingTool(id = "git_commit")
        val plan = Plan.ForTask(
            task = parentTask(),
            tasks = listOf(planStep("step-1-parent-task", "stage files", toolId = "git_stage")),
            estimatedComplexity = 1,
            expectations = Expectations.blank,
        )
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> plan }
            onToolExecution { _, _ -> error("must not be reached when toolId is unknown") }
        }
        val agent = SparkBasedAgent.Code(
            agentId = "routing-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(parentTask())

        assertEquals(0, recorder.invocations.size, "no tool should be invoked on routing failure")
        assertTrue(
            outcome is Outcome.Failure,
            "missing tool routing should bubble up as a failure outcome; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `plan step with null toolId is treated as a no-op reasoning step`() {
        val recorder = RecordingTool(id = "git_commit")
        val plan = Plan.ForTask(
            task = parentTask(),
            tasks = listOf(planStep("step-1-parent-task", "think about it", toolId = null)),
            estimatedComplexity = 1,
            expectations = Expectations.blank,
        )
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> plan }
            onToolExecution { _, _ -> error("must not be reached when toolId is null") }
        }
        val agent = SparkBasedAgent.Code(
            agentId = "routing-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(parentTask())

        assertEquals(0, recorder.invocations.size, "no-op steps must not invoke any tool")
        assertTrue(
            outcome is Outcome.Success,
            "a plan of pure-reasoning steps should still succeed",
        )
    }

    @Test
    fun `multiple steps dispatch to their respective tools in order`() {
        val first = RecordingTool(id = "git_stage")
        val second = RecordingTool(id = "git_commit")
        val plan = Plan.ForTask(
            task = parentTask(),
            tasks = listOf(
                planStep("step-1-parent-task", "stage", toolId = "git_stage"),
                planStep("step-2-parent-task", "commit", toolId = "git_commit"),
            ),
            estimatedComplexity = 2,
            expectations = Expectations.blank,
        )
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> plan }
            onToolExecution { tool, request ->
                val recorder = when (tool.id) {
                    first.id -> first
                    second.id -> second
                    else -> error("unexpected tool id ${tool.id}")
                }

                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { recorder.tool.execute(typed) } as ExecutionOutcome
            }
        }
        val agent = SparkBasedAgent.Code(
            agentId = "routing-agent",
            tools = setOf<Tool<*>>(first.tool, second.tool),
            reasoningOverride = reasoning,
        )

        agent.runLLMToExecuteTask(parentTask())

        assertEquals(1, first.invocations.size, "git_stage should fire once")
        assertEquals(1, second.invocations.size, "git_commit should fire once")
    }
}
