package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.expectation.Expectations
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool

/**
 * AMPR-410: `SparkBasedAgent.executePlanStep` dispatches a [Task.Step] exactly
 * as it dispatches a [Task.CodeChange].
 *
 * Every case here is the [Task.Step] half of a case
 * [SparkBasedAgentStepRoutingTest] already pins for [Task.CodeChange] — the
 * nominated tool fires once, an unknown tool id fails fast with no keyword
 * fallback, a null tool id is a reasoning no-op — because "dispatches exactly
 * like `CodeChange`" is only true if both halves agree. Before this type
 * existed, the second branch of `executePlanStep` rejected everything that was
 * not a `Task.CodeChange` as an unsupported step type, so a generic step failed
 * critically and took the rest of its plan down with it.
 *
 * `runBlocking` rather than `runTest`: the agent's phase lambdas hop to the IO
 * dispatcher inside `withTimeout`, which a virtual clock walks straight past.
 */
class SparkBasedAgentGenericStepDispatchTest {

    private val phaseSparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

    @Test
    fun `a generic step nominating an available tool invokes that tool exactly once`() {
        val recorder = RecordingTool(id = "git_commit")
        val agent = agentWith(recorder)

        val outcome = agent.runLLMToExecuteTask(
            genericStep("step-1-parent-task", "commit changes", toolId = "git_commit"),
            emptyList(),
        )

        assertEquals(1, recorder.invocations.size, "the nominated tool should be invoked exactly once")
        assertTrue(
            outcome is Outcome.Success,
            "the run should succeed when the tool succeeded; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a generic step nominating an unknown toolId fails fast`() {
        val recorder = RecordingTool(id = "git_commit")
        val agent = agentWith(recorder, toolExecutionAllowed = false)

        val outcome = agent.runLLMToExecuteTask(
            genericStep("step-1-parent-task", "stage files", toolId = "git_stage"),
            emptyList(),
        )

        assertEquals(0, recorder.invocations.size, "no tool should be invoked on routing failure")
        assertTrue(
            outcome is Outcome.Failure,
            "an unknown tool id is a failure for a generic step too; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a generic step with a null toolId is carried out by a model call`() {
        // AMPR-407 made a tool-less step one model call rather than a success returned on
        // its behalf, and AMPR-410 has to hold that for a generic step too: the branch is
        // reached through the same `toolId == null` test, so a `Task.Step` that nominates
        // nothing must be carried out, not reported done.
        val recorder = RecordingTool(id = "git_commit")
        val reasoningPrompts = mutableListOf<String>()
        val agent = agentWith(
            recorder,
            toolExecutionAllowed = false,
            onReasoningCall = { prompt ->
                reasoningPrompts += prompt
                "Start from the failing test."
            },
        )

        val outcome = agent.runLLMToExecuteTask(
            genericStep("step-1-parent-task", "think about it", toolId = null),
            emptyList(),
        )

        assertEquals(0, recorder.invocations.size, "a reasoning step must not invoke any tool")
        assertEquals(1, reasoningPrompts.size, "exactly one model call carries the step out")
        assertTrue(
            reasoningPrompts.single().contains("think about it"),
            "the generic step's own description is what the call asks about; got " +
                reasoningPrompts.single(),
        )
        assertTrue(
            outcome is Outcome.Success,
            "the reasoning step reached a conclusion, so it succeeded; " +
                "got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `the step the tool is handed is the generic step itself`() {
        val recorder = RecordingTool(id = "git_commit")
        val agent = agentWith(recorder)
        val step = genericStep(
            id = "step-1-parent-task",
            description = "commit changes",
            toolId = "git_commit",
            assignedTo = AssignedTo.Agent("committer"),
        )

        agent.runLLMToExecuteTask(step, emptyList())

        val request = recorder.invocations.single()
        assertEquals(
            step,
            request.context.task,
            "the step rides on the request unflattened, which is how its inline arguments " +
                "and its seat reach the engine",
        )
        assertEquals(
            "commit changes",
            request.context.instructions,
            "and its description is the intent the tool's strategy works from",
        )
    }

    @Test
    fun `a step's arguments reach the request the tool is dispatched with`() {
        // AMPR-410 is one of the two writers of `ExecutionRequest.arguments`; AMPR-411's
        // `SchemaParameterStrategy` is the other, and `ToolExecutionEngine` validates that
        // one field against the tool's schema without ever looking at the task. So the step
        // carrying them is not enough — they have to be copied onto the request, or the
        // engine spends the parameter call the plan already answered.
        val recorder = RecordingTool(id = "git_commit")
        val agent = agentWith(recorder)
        val arguments = buildJsonObject {
            put("message", "Ship the feature")
            put("signoff", true)
        }

        agent.runLLMToExecuteTask(
            Task.Step(
                id = "step-1-parent-task",
                status = TaskStatus.Pending,
                description = "commit changes",
                toolId = "git_commit",
                arguments = arguments,
            ),
            emptyList(),
        )

        assertEquals(
            arguments,
            recorder.invocations.single().arguments,
            "the arguments the plan stated are the ones the engine gets to settle the call with",
        )
    }

    @Test
    fun `a step stating no arguments leaves the request's arguments null`() {
        val recorder = RecordingTool(id = "git_commit")
        val agent = agentWith(recorder)

        agent.runLLMToExecuteTask(
            genericStep("step-1-parent-task", "commit changes", toolId = "git_commit"),
            emptyList(),
        )

        assertNull(
            recorder.invocations.single().arguments,
            "null means nothing is known for this call, which is what sends it to the strategy",
        )
    }

    @Test
    fun `a CodeChange step cannot state arguments so the request states none`() {
        val recorder = RecordingTool(id = "git_commit")
        val agent = agentWith(recorder)

        agent.runLLMToExecuteTask(
            Task.CodeChange(
                id = "step-1-parent-task",
                status = TaskStatus.Pending,
                description = "commit changes",
                toolId = "git_commit",
            ),
            emptyList(),
        )

        assertNull(recorder.invocations.single().arguments)
    }

    @Test
    fun `a plan of generic steps dispatches each to its own tool`() {
        val first = RecordingTool(id = "git_stage")
        val second = RecordingTool(id = "git_commit")
        val plan = Plan.ForTask(
            task = Task.Step(
                id = "parent-task",
                status = TaskStatus.Pending,
                description = "do the work",
            ),
            tasks = listOf(
                genericStep("step-1-parent-task", "stage", toolId = "git_stage"),
                genericStep("step-2-parent-task", "commit", toolId = "git_commit"),
            ),
            estimatedComplexity = 2,
            expectations = Expectations.blank,
        )
        val reasoning = AgentReasoning.createForTesting(executorId = "generic-step-test") {
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
            agentId = "generic-step-agent",
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

    // ========================================================================
    // Fixture
    // ========================================================================

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

    private fun genericStep(
        id: String,
        description: String,
        toolId: String?,
        assignedTo: AssignedTo? = null,
    ): Task.Step = Task.Step(
        id = id,
        status = TaskStatus.Pending,
        description = description,
        toolId = toolId,
        assignedTo = assignedTo,
    )

    private fun agentWith(
        recorder: RecordingTool,
        toolExecutionAllowed: Boolean = true,
        onReasoningCall: ((String) -> String)? = null,
    ): SparkBasedAgent<CodeState> {
        val reasoning = AgentReasoning.createForTesting(executorId = "generic-step-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, request ->
                check(toolExecutionAllowed) { "no tool should have been dispatched" }

                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { recorder.tool.execute(typed) } as ExecutionOutcome
            }
            onReasoningCall?.let { handler -> onLLMCall { prompt -> handler(prompt) } }
        }
        return SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "generic-step-agent",
            tools = setOf(recorder.tool),
            reasoningOverride = reasoning,
        )
    }
}
