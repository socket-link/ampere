package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
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
 * [SparkBasedAgent.runLLMToExecuteTask]: a plan step dispatches strictly by
 * `Task.CodeChange.toolId` into the agent's `effectiveTools`, with no
 * keyword-routing fallback when the tool id is missing or unknown.
 * (AMPR-400 moved the lookup set from `requiredTools` to the spark-narrowed
 * `effectiveTools`; the git tools these cases nominate are permitted by the
 * `role-code` fixture, so the routing contract they pin is unchanged.)
 *
 * Since AMPR-396 the step handed to `runLLMToExecuteTask` is the step that
 * gets dispatched — the method does not re-plan it first — so every mock
 * reasoning instance here refuses planning outright. Re-planning is the
 * separate, opt-in [SparkBasedAgent.runSubPlanForTask] sub-cycle, covered by
 * [SparkBasedAgentPlanStepDispatchTest].
 *
 * The test wires a mock reasoning instance so it can observe tool invocations
 * without standing up a real LLM or git workspace.
 */
class SparkBasedAgentStepRoutingTest {

    private val phaseSparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

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
    fun `a step nominating an available tool invokes that tool exactly once`() {
        val recorder = RecordingTool(id = "git_commit")
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, request ->
                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { recorder.tool.execute(typed) } as ExecutionOutcome
            }
        }
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "routing-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(
            planStep("step-1-parent-task", "commit changes", toolId = "git_commit"),
        )

        assertEquals(1, recorder.invocations.size, "the nominated tool should be invoked exactly once")
        assertTrue(
            outcome is Outcome.Success,
            "the run should succeed when the tool succeeded; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a step nominating an unknown toolId fails fast with a clear error`() {
        val recorder = RecordingTool(id = "git_commit")
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, _ -> error("must not be reached when toolId is unknown") }
        }
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "routing-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(
            planStep("step-1-parent-task", "stage files", toolId = "git_stage"),
        )

        assertEquals(0, recorder.invocations.size, "no tool should be invoked on routing failure")
        assertTrue(
            outcome is Outcome.Failure,
            "missing tool routing should bubble up as a failure outcome; got ${outcome::class.simpleName}",
        )
    }

    /**
     * AMPR-407: a tool-less step invokes no *tool*, which is not the same as
     * invoking nothing. It is carried out by one model call, and a seat that
     * cannot make that call reports a failure rather than a success it did not
     * earn.
     */
    @Test
    fun `a step with null toolId is carried out by a model call instead of a tool`() {
        val recorder = RecordingTool(id = "git_commit")
        val reasoningPrompts = mutableListOf<String>()
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, _ -> error("must not be reached when toolId is null") }
            onLLMCall { prompt ->
                reasoningPrompts += prompt
                "Start from the failing test."
            }
        }
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "routing-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(
            planStep("step-1-parent-task", "think about it", toolId = null),
        )

        assertEquals(0, recorder.invocations.size, "a reasoning step must not invoke any tool")
        assertEquals(1, reasoningPrompts.size, "exactly one model call carries the step out")
        assertTrue(
            reasoningPrompts.single().contains("think about it"),
            "the step's own description is what the call asks about; got " +
                reasoningPrompts.single(),
        )
        assertTrue(
            outcome is Outcome.Success,
            "the reasoning step reached a conclusion, so it succeeded; " +
                "got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a reasoning step with no model to call fails rather than reporting success`() {
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, _ -> error("must not be reached when toolId is null") }
        }
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "routing-agent",
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(
            planStep("step-1-parent-task", "decide the approach", toolId = null),
        )

        assertTrue(
            outcome is Outcome.Failure,
            "a step nobody performed is not a step that succeeded (AMPR-407); " +
                "got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `executePlan dispatches every step to its own tool exactly once`() {
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
            onPlanning { _, _ -> error("executePlan must not re-plan its own steps (AMPR-396)") }
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
            sparkRegistry = phaseSparkLibrary,
            agentId = "routing-agent",
            tools = setOf<Tool<*>>(first.tool, second.tool),
            reasoningOverride = reasoning,
        )

        val outcome = runBlocking { agent.executePlan(plan) }

        assertEquals(1, first.invocations.size, "git_stage should fire once")
        assertEquals(1, second.invocations.size, "git_commit should fire once")
        assertTrue(
            outcome is Outcome.Success,
            "both steps succeeded, so the plan should; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `runSubPlanForTask is the opt-in cycle that re-plans a coarse task`() {
        val recorder = RecordingTool(id = "git_commit")
        val subPlan = Plan.ForTask(
            task = parentTask(),
            tasks = listOf(planStep("step-1-parent-task", "commit changes", toolId = "git_commit")),
            estimatedComplexity = 1,
            expectations = Expectations.blank,
        )
        var planningCalls = 0
        val reasoning = AgentReasoning.createForTesting(executorId = "routing-test") {
            onPlanning { _, _ ->
                planningCalls++
                subPlan
            }
            onToolExecution { _, request ->
                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { recorder.tool.execute(typed) } as ExecutionOutcome
            }
        }
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "routing-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runSubPlanForTask(parentTask())

        assertEquals(1, planningCalls, "the opt-in sub-cycle plans exactly once")
        assertEquals(1, recorder.invocations.size, "the sub-plan's step should be dispatched")
        assertTrue(
            outcome is Outcome.Success,
            "the sub-plan succeeded, so the outcome should; got ${outcome::class.simpleName}",
        )
    }
}
