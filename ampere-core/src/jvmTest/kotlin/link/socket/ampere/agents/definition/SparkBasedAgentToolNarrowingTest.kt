package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId
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
import link.socket.ampere.agents.execution.tools.planning.PLAN_STEPS_TOOL_ID
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.llm.LlmProvider

/**
 * AMPR-400: the spark stack's tool narrowing is *enforced*, not merely computed.
 *
 * `AutonomousAgent.availableTools` has always reported what the stack permits,
 * but planning and dispatch both read `requiredTools` — so a spark declaring
 * `allowedTools` narrowed nothing. These tests pin both ends of the fix:
 * `PlanGenerator` is offered only the narrowed set, and `executePlanStep`
 * dispatches only against it.
 *
 * There is deliberately *one* lookup set behind dispatch, so a tool the stack
 * withdrew and a tool that never existed fail on the same line with the same
 * message. The tests below assert the observable half of that (failure outcome,
 * nothing invoked); the single-path property is structural.
 *
 * Since AMPR-396 the dispatch cases hand `runLLMToExecuteTask` the step itself
 * rather than a task it re-plans, so their mock reasoning refuses planning. What
 * a denied step does to the *rest* of a plan moved with it — see
 * `a denied step fails the plan without stopping its later steps`.
 */
class SparkBasedAgentToolNarrowingTest {

    private companion object {
        const val ALPHA = "tool_alpha"
        const val BETA = "tool_beta"

        /** A one-step, tool-less plan — enough to satisfy the plan parser. */
        val PLAN_JSON = """
            {
              "steps": [
                {
                  "description": "think it through",
                  "toolToUse": null,
                  "requiresPreviousStep": false
                }
              ],
              "estimatedComplexity": 1
            }
        """.trimIndent()
    }

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider should not be called when custom provider is set")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    /**
     * A capability-bearing spark shaped like a declarative role spark: it
     * permits exactly [allowedTools] and says nothing about any tool in its
     * prompt, so a tool id appearing in the payload can only have come from the
     * planner's available-tools list.
     */
    private data class NarrowingSpark(
        override val allowedTools: Set<ToolId>?,
    ) : Spark {
        override val name: String = "Role:Narrow"
        override val promptContribution: String = "Operate within your granted capability."
        override val fileAccessScope: FileAccessScope? = null
    }

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

    private fun parentTask(): Task.CodeChange =
        Task.CodeChange(
            id = "parent-task",
            status = TaskStatus.Pending,
            description = "do the work",
        )

    /**
     * One plan step nominating [toolId]. Since AMPR-396 this is what
     * `runLLMToExecuteTask` is handed and what it dispatches — it no longer
     * re-plans the task into a sub-plan first.
     */
    private fun step(toolId: String?): Task.CodeChange =
        Task.CodeChange(
            id = "step-1-parent-task",
            status = TaskStatus.Pending,
            description = "act 0",
            toolId = toolId,
        )

    private fun planOf(vararg toolIds: String?): Plan.ForTask =
        Plan.ForTask(
            task = parentTask(),
            tasks = toolIds.mapIndexed { index, toolId ->
                Task.CodeChange(
                    id = "step-${index + 1}-parent-task",
                    status = TaskStatus.Pending,
                    description = "act $index",
                    toolId = toolId,
                )
            },
            estimatedComplexity = toolIds.size,
            expectations = Expectations.blank,
        )

    private fun agentWith(
        tools: Set<Tool<*>>,
        narrowing: Set<ToolId>?,
        llmProvider: LlmProvider? = null,
        reasoningOverride: AgentReasoning? = null,
    ): SparkBasedAgent<CodeState> {
        val agent = SparkBasedAgent(
            agentId = "narrowing-agent",
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _additionalTools = tools,
            _aiConfiguration = FakeAIConfiguration(),
            _llmProvider = llmProvider,
            _reasoningOverride = reasoningOverride,
        )
        if (narrowing != null) {
            agent.spark<SparkBasedAgent<CodeState>>(NarrowingSpark(narrowing))
        }
        return agent
    }

    @Test
    fun `effectiveTools is requiredTools intersected with what the stack permits`() {
        val alpha = RecordingTool(ALPHA)
        val beta = RecordingTool(BETA)
        val agent = agentWith(
            tools = setOf(alpha.tool, beta.tool),
            narrowing = setOf(ALPHA),
        )

        assertEquals(
            setOf(ALPHA),
            agent.effectiveTools.map { it.id }.toSet(),
            "a spark permitting only $ALPHA should withdraw both $BETA and $PLAN_STEPS_TOOL_ID",
        )
        assertTrue(
            agent.requiredTools.map { it.id }.containsAll(setOf(ALPHA, BETA, PLAN_STEPS_TOOL_ID)),
            "requiredTools itself is untouched — narrowing is a view over it, not a mutation",
        )
    }

    @Test
    fun `an unconstrained stack leaves every built-in tool effective`() {
        val alpha = RecordingTool(ALPHA)
        val beta = RecordingTool(BETA)
        val agent = agentWith(
            tools = setOf(alpha.tool, beta.tool),
            narrowing = null,
        )

        assertEquals(
            setOf(ALPHA, BETA, PLAN_STEPS_TOOL_ID),
            agent.effectiveTools.map { it.id }.toSet(),
            "no spark constrains tools, so the full required set stays effective",
        )
    }

    @Test
    fun `a narrowed-out tool never appears in the planning prompt`() {
        val captured = CopyOnWriteArrayList<String>()
        val alpha = RecordingTool(ALPHA)
        val beta = RecordingTool(BETA)
        val agent = agentWith(
            tools = setOf(alpha.tool, beta.tool),
            narrowing = setOf(ALPHA),
            llmProvider = { prompt ->
                captured += prompt
                PLAN_JSON
            },
        )

        val plan = agent.runLLMToPlan(parentTask(), emptyList(), emptyList())

        assertEquals(1, captured.size, "planning should have made exactly one model call")
        val payload = captured.single()
        assertTrue(
            payload.contains(ALPHA),
            "the permitted tool should be offered to the planner; got: $payload",
        )
        assertFalse(
            payload.contains(BETA),
            "the narrowed-out tool must not be offered to the planner; got: $payload",
        )
        assertTrue(
            plan is Plan.ForTask && plan.tasks.size == 1,
            "the canned plan should have parsed rather than falling back; got $plan",
        )
    }

    @Test
    fun `a plan step nominating a narrowed-out tool fails fast without invoking it`() {
        val alpha = RecordingTool(ALPHA)
        val beta = RecordingTool(BETA)
        val reasoning = AgentReasoning.createForTesting(executorId = "narrowing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, _ -> error("no tool should be reached once a step is denied") }
        }
        val agent = agentWith(
            tools = setOf(alpha.tool, beta.tool),
            narrowing = setOf(ALPHA),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(step(BETA))

        assertTrue(
            outcome is Outcome.Failure,
            "a step naming a withdrawn tool should fail the run; got ${outcome::class.simpleName}",
        )
        assertEquals(0, beta.invocations.size, "the withdrawn tool must never be dispatched")
        assertEquals(0, alpha.invocations.size, "no other tool stands in for the denied one")
    }

    /**
     * The denial is per step, and a plan's later steps still run.
     *
     * This used to read the other way: [SparkBasedAgent.runLLMToExecuteTask]
     * re-planned each task into a sub-plan, and `PlanExecutor`'s critical-failure
     * short-circuit then skipped the rest *of that sub-plan*. It never applied
     * across a Plan phase plan's own steps — `AutonomousAgent.executePlan` maps
     * every task eagerly and only its reduce prefers the first non-success — so
     * the skip was a property of the sub-plan AMPR-396 deleted, not of plan
     * execution. Pinned here as the behaviour that actually holds; making a
     * critical step abort the plan is a separate decision, and `Outcome` carries
     * no criticality at this level to make it on.
     */
    @Test
    fun `a denied step fails the plan without stopping its later steps`() {
        val alpha = RecordingTool(ALPHA)
        val beta = RecordingTool(BETA)
        val reasoning = AgentReasoning.createForTesting(executorId = "narrowing-test") {
            onPlanning { _, _ -> error("executePlan must not re-plan its own steps (AMPR-396)") }
            onToolExecution { _, request ->
                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { alpha.tool.execute(typed) } as ExecutionOutcome
            }
        }
        val agent = agentWith(
            tools = setOf(alpha.tool, beta.tool),
            narrowing = setOf(ALPHA),
            reasoningOverride = reasoning,
        )

        val outcome = runBlocking { agent.executePlan(planOf(BETA, ALPHA)) }

        assertTrue(
            outcome is Outcome.Failure,
            "the denied step's failure is the one reported; got ${outcome::class.simpleName}",
        )
        assertEquals(0, beta.invocations.size, "the withdrawn tool must never be dispatched")
        assertEquals(1, alpha.invocations.size, "the permitted step that follows it still runs")
    }

    @Test
    fun `the same plan succeeds when no spark withdraws the tool`() {
        val beta = RecordingTool(BETA)
        val reasoning = AgentReasoning.createForTesting(executorId = "narrowing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, request ->
                @Suppress("UNCHECKED_CAST")
                val typed = request as ExecutionRequest<ExecutionContext.NoChanges>
                runBlocking { beta.tool.execute(typed) } as ExecutionOutcome
            }
        }
        val agent = agentWith(
            tools = setOf(beta.tool),
            narrowing = null,
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(step(BETA))

        assertEquals(
            1,
            beta.invocations.size,
            "without narrowing the step dispatches normally — the denial above was the spark's doing",
        )
        assertTrue(
            outcome is Outcome.Success,
            "the unnarrowed run should succeed; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a spark pushed after the first plan narrows the next one`() {
        val captured = CopyOnWriteArrayList<String>()
        val alpha = RecordingTool(ALPHA)
        val beta = RecordingTool(BETA)
        val agent = agentWith(
            tools = setOf(alpha.tool, beta.tool),
            narrowing = null,
            llmProvider = { prompt ->
                captured += prompt
                PLAN_JSON
            },
        )

        agent.runLLMToPlan(parentTask(), emptyList(), emptyList())
        assertTrue(
            captured.last().contains(BETA),
            "before any narrowing spark, every tool is on offer; got: ${captured.last()}",
        )

        agent.spark<SparkBasedAgent<CodeState>>(NarrowingSpark(setOf(ALPHA)))
        agent.runLLMToPlan(parentTask(), emptyList(), emptyList())

        assertFalse(
            captured.last().contains(BETA),
            "the reasoning unit outlives the stack change, so it must re-read the narrowed " +
                "set rather than the one captured when it was built; got: ${captured.last()}",
        )
    }

    @Test
    fun `a step nominating a tool the agent never had still fails the same way`() {
        val alpha = RecordingTool(ALPHA)
        val reasoning = AgentReasoning.createForTesting(executorId = "narrowing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, _ -> error("an unknown tool id must not reach dispatch") }
        }
        val agent = agentWith(
            tools = setOf(alpha.tool),
            narrowing = setOf(ALPHA),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(step("tool_that_never_existed"))

        assertTrue(
            outcome is Outcome.Failure,
            "an unknown tool id fails on the same lookup as a withdrawn one; " +
                "got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a narrowing spark does not disturb a tool-less reasoning step`() {
        val alpha = RecordingTool(ALPHA)
        val reasoning = AgentReasoning.createForTesting(executorId = "narrowing-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, _ -> error("a null toolId invokes no tool") }
            // AMPR-407: a tool-less step is one model call by the executing seat.
            onLLMCall { "The approach is to narrow nothing." }
        }
        val agent = agentWith(
            tools = setOf(alpha.tool),
            narrowing = emptySet(),
            reasoningOverride = reasoning,
        )

        val outcome = agent.runLLMToExecuteTask(step(null))

        assertTrue(
            outcome is Outcome.Success,
            "pure-reasoning steps are not gated by the tool set; got ${outcome::class.simpleName}",
        )
    }
}
