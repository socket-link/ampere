package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.config.ReasoningStepConfig
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.llm.LlmProvider

/**
 * AMPR-407: a plan step that nominates no tool is work, not a formality.
 *
 * `executePlanStep` used to answer `StepResult.success(details = "reasoning step (no
 * toolToUse)")` for any step whose `toolId` was null, without a model call — so a plan
 * of *"1. decide the approach, 2. write the file"* had its first step done by nobody and
 * reported done. The planning prompt invites exactly those steps, so the fix is to carry
 * them out: one model call by the executing seat, filed under `EXECUTE` like every other
 * call Execute makes, whose text becomes the step's result and reaches the steps after it.
 *
 * Driven through the production wiring — a real `AgentReasoning` built from the agent's own
 * configuration, a real door, a real `FunctionExecutor` — so the model calls counted here
 * are the ones a run would be billed for. The recording tool deliberately ships no
 * `ParameterStrategy`, which keeps its dispatch model-call-free and leaves the reasoning
 * step as the only thing that can have made one.
 *
 * AMPR-412 (H17) is the other half: what the earlier steps produced reaches the reasoning
 * step's prompt, and what the reasoning step concluded reaches the later steps, through the
 * one prior-results chain rather than a carrier of its own.
 *
 * `runBlocking`, not `runTest`: the agent's phase lambdas hop to the IO dispatcher inside
 * `withTimeout`, and a virtual clock walks straight past that.
 */
class SparkBasedAgentReasoningStepTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    /** Every prompt that reached the (fake) model, in order. */
    private val prompts = CopyOnWriteArrayList<String>()

    /** Every request the recording tool was dispatched with. */
    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        // Cancel before closing: the agent's observability scope is this scope, so a spark
        // snapshot still in flight would otherwise write to a closed driver.
        scope.cancel()
        door.close()
    }

    @Test
    fun `a reasoning step makes one EXECUTE call and its text reaches the next step`() {
        val agent = agent(answering = REASONED_TEXT)
        val plan = planOf(
            step("step-1", "Decide the approach", toolId = null),
            step("step-2", "Write the file", toolId = TOOL_ID),
        )

        val outcome = runBlocking { agent.executePlan(plan) }

        // One model call, and it is the reasoning step's: the tool step dispatches with no
        // parameter strategy, so it spends nothing on the model.
        assertEquals(1, prompts.size, "exactly one model call for the plan, got: $prompts")
        val reasoningPrompt = prompts.single()
        assertTrue(
            reasoningPrompt.contains("Decide the approach"),
            "the call asks about the step it is carrying out; got:\n$reasoningPrompt",
        )
        assertTrue(
            reasoningPrompt.contains("nominates no tool"),
            "and says whose work the step is; got:\n$reasoningPrompt",
        )

        // Filed under EXECUTE, under the run — the same way the parameter-strategy call is.
        val executeCalls = runBlocking { providerCalls() }
            .filter { it.cognitivePhase == CognitivePhase.EXECUTE }
        assertEquals(1, executeCalls.size, "the reasoning call is tagged EXECUTE, not UNKNOWN")
        assertEquals(
            RUN_ID,
            executeCalls.single().workflowId,
            "and carries the run, which is what ArcTraceProjection joins on",
        )

        // The step's text is what step two is asked to act on (B14): it rides the request as
        // one of the prior results, the one carrier every parameter strategy renders
        // (AMPR-412), rather than being folded into the step's own instructions.
        val request = assertNotNull(
            dispatched.singleOrNull(),
            "the tool step should have dispatched exactly once",
        )
        val carried = assertNotNull(
            request.priorResults.singleOrNull(),
            "one step ran before the tool step; got ${request.priorResults}",
        )
        assertIs<StepOutcome.Success>(carried, "got $carried")
        assertEquals("Decide the approach", carried.stepDescription)
        assertEquals(REASONED_TEXT, carried.details)
        assertEquals(
            "Write the file",
            request.context.instructions,
            "the step's own description stays the call's intent, undisplaced",
        )

        // And it is the step's reported result, so the trace carries it too.
        val completed = runBlocking { planStepCompletions() }
        val reasoningOutcome = assertNotNull(
            completed.firstOrNull { it.stepId == "step-1" }?.outcome,
            "the reasoning step should have completed; got ${completed.map { it.stepId }}",
        )
        assertIs<StepOutcome.Success>(reasoningOutcome, "got $reasoningOutcome")
        assertEquals(REASONED_TEXT, reasoningOutcome.details)

        assertTrue(
            outcome is Outcome.Success,
            "both steps did their work, so the plan succeeded; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a reasoning step that produces no text fails rather than succeeding silently`() {
        val agent = agent(answering = "   \n  ")

        val outcome = runBlocking {
            agent.executePlan(planOf(step("step-1", "Decide the approach", toolId = null)))
        }

        assertEquals(1, prompts.size, "the call was made")
        assertTrue(
            outcome is Outcome.Failure,
            "a step that reached no conclusion has nothing for the later steps to read, " +
                "so it is not a success; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a host can declare reasoning steps free`() {
        val agent = agent(
            answering = REASONED_TEXT,
            cognitiveConfig = CognitiveConfig(reasoningSteps = ReasoningStepConfig(execute = false)),
        )

        val outcome = runBlocking {
            agent.executePlan(planOf(step("step-1", "Decide the approach", toolId = null)))
        }

        assertEquals(
            0,
            prompts.size,
            "`reasoningSteps.execute = false` is the host saying tool-less steps cost nothing",
        )
        assertTrue(
            outcome is Outcome.Success,
            "it is also the host accepting them as done; got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `a later reasoning step is told what the earlier one concluded`() {
        val agent = agent(answering = REASONED_TEXT)
        val plan = planOf(
            step("step-1", "Decide the approach", toolId = null),
            step("step-2", "Check the decision", toolId = null),
        )

        runBlocking { agent.executePlan(plan) }

        assertEquals(2, prompts.size, "both steps are carried out, one call each")
        val secondPrompt = prompts.last()
        assertTrue(
            secondPrompt.contains("Results of the steps already executed"),
            "the second step is handed the chain under the heading every strategy uses; got:\n" +
                secondPrompt,
        )
        assertTrue(
            secondPrompt.contains("Decide the approach") && secondPrompt.contains(REASONED_TEXT),
            "and the chain names the earlier step and what it concluded; got:\n$secondPrompt",
        )
        assertFalse(
            prompts.first().contains("Results of the steps already executed"),
            "nothing ran before the first step, so it reads no chain; got:\n${prompts.first()}",
        )
    }

    @Test
    fun `a reasoning step's prompt carries the previous tool step's result`() {
        val agent = agent(answering = REASONED_TEXT)
        val plan = planOf(
            step("step-1", "Write the file", toolId = TOOL_ID),
            step("step-2", "Review what was written", toolId = null),
        )

        val outcome = runBlocking { agent.executePlan(plan) }

        // The tool step ships no strategy, so the one model call is the reasoning step's
        // (AMPR-412, H17): what a tool returned reaches the step that reasons after it.
        assertEquals(1, prompts.size, "exactly one model call for the plan, got: $prompts")
        val reasoningPrompt = prompts.single()
        assertTrue(
            reasoningPrompt.contains("Results of the steps already executed"),
            "the reasoning step is handed the chain; got:\n$reasoningPrompt",
        )
        assertTrue(
            reasoningPrompt.contains("Write the file") && reasoningPrompt.contains(TOOL_RESULT),
            "and the chain carries the tool step and what its tool reported — the payload, " +
                "not a type name; got:\n$reasoningPrompt",
        )
        assertTrue(
            reasoningPrompt.indexOf(TOOL_RESULT) < reasoningPrompt.indexOf("Answer the step itself"),
            "the results come before the ask, so the model ends on the step; got:\n$reasoningPrompt",
        )
        assertTrue(
            outcome is Outcome.Success,
            "both steps did their work, so the plan succeeded; got ${outcome::class.simpleName}",
        )
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun agent(
        answering: String,
        cognitiveConfig: CognitiveConfig = CognitiveConfig(),
    ): SparkBasedAgent<CodeState> {
        val provider: LlmProvider = { prompt ->
            prompts += prompt
            answering
        }
        return SparkBasedAgent(
            agentId = AGENT_ID,
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _additionalTools = setOf(recordingTool),
            _eventApi = door.api,
            _aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_5,
            ),
            _llmProvider = provider,
            _observabilityScope = scope,
            _executor = FunctionExecutor.create(),
            _runId = RUN_ID,
            _cognitiveConfig = cognitiveConfig,
        )
    }

    /** Records what it was dispatched with and succeeds. Ships no `ParameterStrategy`. */
    private val recordingTool = FunctionTool<ExecutionContext.NoChanges>(
        id = TOOL_ID,
        name = "Recording $TOOL_ID",
        description = "test recording tool",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            dispatched += request
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = TOOL_RESULT,
            )
        },
    )

    private suspend fun providerCalls(): List<ProviderCallStartedEvent> =
        door.repository.getAllEvents().getOrThrow().filterIsInstance<ProviderCallStartedEvent>()

    private suspend fun planStepCompletions(): List<PlanEvent.PlanStepCompleted> =
        door.repository.getAllEvents().getOrThrow().filterIsInstance<PlanEvent.PlanStepCompleted>()

    private fun step(id: String, description: String, toolId: String?): Task.CodeChange =
        Task.CodeChange(
            id = id,
            status = TaskStatus.Pending,
            description = description,
            toolId = toolId,
        )

    private fun planOf(vararg steps: Task): Plan.ForTask = Plan.ForTask(
        task = step("parent-task", PARENT_DESCRIPTION, toolId = null),
        tasks = steps.toList(),
        estimatedComplexity = steps.size,
    )

    private companion object {
        const val AGENT_ID = "reasoning-step-agent"
        const val RUN_ID = "run-ampr-407"
        const val TOOL_ID = "write_the_file"
        const val PARENT_DESCRIPTION = "Add a retry to the uploader"
        const val REASONED_TEXT = "Wrap the upload call in an exponential backoff."
        const val TOOL_RESULT = "wrote Uploader.kt with a backoff loop around the upload call"
    }
}
