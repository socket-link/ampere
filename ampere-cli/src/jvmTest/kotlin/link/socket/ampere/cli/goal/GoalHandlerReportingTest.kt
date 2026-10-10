package link.socket.ampere.cli.goal

import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.AmpereContext
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.cli.layout.AgentMemoryPane
import link.socket.ampere.cli.layout.CognitiveProgressPane
import link.socket.ampere.domain.llm.LlmProvider
import org.junit.jupiter.api.Test

/**
 * AMPR-405 (B11): `--goal` reports what actually happened.
 *
 * The goal path used to print COMPLETED for a cycle that changed nothing. Two things made
 * that possible, and both are covered here:
 *
 * 1. No shipped path supplied an `Executor`, so every tool step came back refused — and the
 *    refusals arrived as `ExecutionOutcome.NoChanges.Failure`, which the handler's `when` did
 *    not match. They fell through its `else` branch and the cycle went on to LEARN and
 *    COMPLETED.
 * 2. The reason never survived the fold. A step reported `tool=X failed: Failure` — the
 *    outcome's variant *name*, not its message — and `PlanExecutor`'s summary counted
 *    failures without listing them, so even a caller that did look had nothing to print.
 *
 * The no-executor refusal is now raised before Perceive, so a cycle that cannot act costs no
 * model call at all. `AgentFactory` supplies a `FunctionExecutor` by default — the other half
 * of the fix — so reaching that guard takes an explicit `executor = null`, which is what the
 * first test asks the factory for. Every model call is answered by a stub `LlmProvider` with a
 * canned one-step plan, so no provider is involved.
 */
class GoalHandlerReportingTest {

    @Test
    fun `an agent that cannot run tools is reported, not completed`() = withGoalHandler { env ->
        val probe = RecordingTool(PROBE_TOOL_ID)
        val agent = env.codeAgent(probe, executor = null)

        env.handler.handleTicketAssignment(agent, env.ticketId)

        assertEquals(
            CognitiveProgressPane.Phase.FAILED,
            env.pane.currentPhase,
            "a goal whose agent cannot dispatch a single tool has not completed",
        )
        assertTrue(
            assertNotNull(env.pane.currentErrorMessage).contains("No executor configured"),
            "the report must name what was missing; got: ${env.pane.currentErrorMessage}",
        )
        assertEquals(
            0,
            env.prompts.size,
            "the refusal comes before Perceive, so the cycle should spend no model call",
        )
    }

    @Test
    fun `an agent that can run tools runs the plan's tool and completes`() = withGoalHandler { env ->
        val probe = RecordingTool(PROBE_TOOL_ID)
        val agent = env.codeAgent(probe, executor = FunctionExecutor.create())

        env.handler.handleTicketAssignment(agent, env.ticketId)

        assertEquals(
            1,
            probe.invocations.size,
            "the canned plan's one step names the probe tool, and the agent has an executor, " +
                "so the tool should have run",
        )
        assertEquals(
            CognitiveProgressPane.Phase.COMPLETED,
            env.pane.currentPhase,
            "a cycle that ran its plan's tool has completed; reported " +
                "${env.pane.currentErrorMessage}",
        )
    }

    /**
     * The other half of "reports what actually happened": a plan that failed every step still
     * settles as `NoChanges.Failure`, which is exactly the shape that used to be reported as
     * COMPLETED. Here the step names a tool the agent does not have, so it fails for a reason
     * that has nothing to do with the executor.
     */
    @Test
    fun `a plan whose steps all failed is reported as failed`() = withGoalHandler { env ->
        val agent = env.codeAgent(tool = null, executor = FunctionExecutor.create())

        env.handler.handleTicketAssignment(agent, env.ticketId)

        assertEquals(
            CognitiveProgressPane.Phase.FAILED,
            env.pane.currentPhase,
            "every step of the plan failed, so the goal did not complete",
        )
        assertTrue(
            assertNotNull(env.pane.currentErrorMessage).contains(PROBE_TOOL_ID),
            "the report should name the step's own error, not just that something failed; " +
                "got: ${env.pane.currentErrorMessage}",
        )
    }

    // ========================================================================
    // Harness
    // ========================================================================

    private class GoalEnv(
        val handler: GoalHandler,
        val pane: CognitiveProgressPane,
        val ticketId: String,
        val prompts: List<String>,
        val scope: CoroutineScope,
        val context: AmpereContext,
    )

    /**
     * Stands up a real [AmpereContext] over a temp database, creates the ticket the cycle is
     * driven against, and tears both down afterwards.
     *
     * `runBlocking` rather than `runTest`: the agent's phase lambdas hop to the IO dispatcher
     * inside a `withTimeout`, which a virtual clock skips straight past.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun withGoalHandler(block: suspend (GoalEnv) -> Unit) = runBlocking<Unit> {
        val tempDir = createTempDirectory("ampr405-goal-reporting")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = AmpereContext(
            databasePath = File(tempDir.toFile(), "goal-reporting.db").absolutePath,
            workspace = ExecutionWorkspace(baseDirectory = tempDir.toString()),
        )
        val pane = CognitiveProgressPane(Terminal())
        try {
            val ticket = context.environmentService.ticketOrchestrator.createTicket(
                title = "Ship the feature",
                description = "Write the code that ships the feature",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = "human-cli",
            ).getOrThrow().first
            val handler = GoalHandler(
                context = context,
                agentScope = scope,
                progressPane = pane,
                memoryPane = AgentMemoryPane(Terminal()),
            )
            block(GoalEnv(handler, pane, ticket.id, prompts, scope, context))
        } finally {
            scope.cancel()
            context.close()
            tempDir.deleteRecursively()
        }
    }

    private val prompts = CopyOnWriteArrayList<String>()

    /** Every model call this cycle makes is answered with the one-step canned plan. */
    private val stubTransport: LlmProvider = { prompt ->
        prompts += prompt
        CANNED_PLAN_JSON
    }

    /**
     * Built through the real [AgentFactory] — the bundled spark library it resolves is
     * internal to `ampere-core`, so the factory is also the only way to get a properly
     * sparked agent from here. `executor` is the one thing varied: the factory's own default
     * is a `FunctionExecutor`, and `null` is how a test asks for the misconfiguration the
     * shipped path no longer produces.
     */
    private fun GoalEnv.codeAgent(
        tool: RecordingTool?,
        executor: Executor?,
    ): SparkBasedAgent<CodeState> = AgentFactory(
        scope = scope,
        ticketOrchestrator = context.environmentService.ticketOrchestrator,
        workspace = context.workspace,
        llmProvider = stubTransport,
        executor = executor,
        additionalTools = setOfNotNull(tool?.tool),
    ).create(AgentType.CODE)

    /** A tool that records every invocation, so "it ran" is observable rather than inferred. */
    private class RecordingTool(id: String) {
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

    private companion object {
        /**
         * A tool id the bundled `role-code` spark's `allowedTools` admits, so the spark stack
         * does not withdraw the probe before dispatch is reached (AMPR-400).
         */
        const val PROBE_TOOL_ID: String = "run_tests"

        val CANNED_PLAN_JSON: String = """
            {
              "steps": [
                {
                  "description": "Run the probe tool",
                  "toolToUse": "$PROBE_TOOL_ID",
                  "requiresPreviousStep": false
                }
              ],
              "estimatedComplexity": 1,
              "requiresHumanInput": false
            }
        """.trimIndent()
    }
}
