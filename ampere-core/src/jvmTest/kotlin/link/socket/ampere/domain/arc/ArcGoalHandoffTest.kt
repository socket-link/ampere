package link.socket.ampere.domain.arc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.trace.ArcTraceProjection
import okio.Path.Companion.toPath

/**
 * AMPR-395: the Flow tick is what hands the user's goal to an agent.
 *
 * `FlowPhase` used to read the tick's task out of the agent's own memory cell, which only
 * `AutonomousAgent.executePlan`/`runTask` ever write and nothing on the Arc path calls — so the
 * task was always `Task.Blank`, `PlanGenerator` returned `Plan.blank` with no model call,
 * `executePlan` returned `Outcome.blank` (not a success), and no run could complete a goal. The
 * goal the person typed reached no agent while every tick still billed a Perceive call.
 *
 * These tests drive the real [AmpereRuntime] against a scripted [UpstreamLlmClient] and cover
 * both halves of the fix: the goal text reaches one PLAN-tagged call per tick, and a successful
 * outcome terminates the run with [TerminationReason.GOAL_COMPLETE].
 *
 * Lives in `jvmTest` because the in-memory SQLDelight driver behind the trace projection
 * (`JdbcSqliteDriver`) is JVM-only — the same choice `ArcRunIdentityIntegrationTest` makes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArcGoalHandoffTest {

    /** Carries no " and " / " then " / ";", so [GoalTreeBuilder] leaves it as a single node. */
    private val goal = "Add a health check endpoint"

    private val eventScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventApiFactory: AgentEventApiFactory

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        eventApiFactory = AgentEventApiFactory(
            eventRepository = EventRepository(DEFAULT_JSON, eventScope, database),
            eventSerialBus = EventSerialBus(eventScope),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `every tick hands the current goal to an agent as the task it plans`() = runTest {
        // Every plan step nominates a tool the agent does not have, so each tick ends in a
        // failure rather than a success. That keeps the goal incomplete and the run ticking,
        // which is what makes "one planning call per tick" observable at all.
        val client = ScriptedUpstreamLlmClient(planStepToolId = "no-such-tool")
        val runId = "ampr395-handoff"

        val completed = assertCompleted(
            runtime(client, maxFlowTicks = 3).execute(goal, runId = runId),
        )

        assertEquals(
            TerminationReason.MAX_TICKS_REACHED,
            completed.flowResult.terminationReason,
            "a failing step must not complete the goal, or this test proves nothing",
        )
        assertEquals(3, completed.flowResult.finalTick)

        assertEquals(
            3,
            client.planningPromptsMentioning(goal).size,
            "exactly one planning call per tick must carry the goal text; before AMPR-395 that " +
                "count was 0 because the tick planned Task.Blank. Planning prompts in total: " +
                client.planningPrompts().size,
        )

        // The tick also assigns the goal, which is the only way the Perceive phase can see it:
        // its context builder reads the agent's memory cell rather than its arguments
        // (AMPR-403), so an unassigned cell would render "(none assigned yet)" every tick.
        assertEquals(
            3,
            client.perceptionPromptsMentioning(goal).size,
            "every tick's Perceive prompt must describe the goal it was assigned",
        )

        // The calls the stub classified as planning are the ones Ampere tagged PLAN: the
        // projection reads `cognitivePhase` off the persisted telemetry, not off the prompt.
        val planInvocations = ArcTraceProjection(database).project(runId).getOrThrow()
            .phases
            .flatMap { it.modelInvocations }
            .filter { it.phaseName == "PLAN" }
        assertTrue(
            planInvocations.isNotEmpty(),
            "a run that reaches an agent must record PLAN-phase model invocations",
        )
        assertEquals(
            client.planningPrompts().size,
            planInvocations.size,
            "every prompt PlanGenerator built must be recorded under the PLAN phase",
        )
    }

    @Test
    fun `a successful outcome completes the goal and terminates the run`() = runTest {
        // Steps nominate no tool, making each a tool-less reasoning step that succeeds.
        val client = ScriptedUpstreamLlmClient(planStepToolId = null)

        val completed = assertCompleted(
            runtime(client, maxFlowTicks = 3).execute(goal, runId = "ampr395-complete"),
        )
        val flow = completed.flowResult

        assertEquals(TerminationReason.GOAL_COMPLETE, flow.terminationReason)
        assertEquals(1, flow.finalTick, "the goal must be met on the first tick, not by exhaustion")
        assertEquals(
            listOf(goal),
            flow.completedGoals.map { it.description },
            "the completed goal is the one the caller asked for",
        )
        assertTrue(
            flow.agentOutcomes.values.flatten().any { it is Outcome.Success },
            "GOAL_COMPLETE must rest on a real success outcome, not on Outcome.blank",
        )
        assertEquals(
            1,
            client.planningPromptsMentioning(goal).size,
            "the goal is planned once: on the tick that completed it",
        )
        assertEquals(
            listOf(goal),
            completed.chargeResult.goalTree.allNodes().map { it.description },
            "a goal with no separator decomposes to a single node, so one task covers the tree",
        )
    }

    private fun TestScope.runtime(client: UpstreamLlmClient, maxFlowTicks: Int): AmpereRuntime =
        AmpereRuntime(
            arcConfig = ArcConfig(
                name = "goal-handoff-arc",
                agents = listOf(ArcAgentConfig(role = "code")),
            ),
            projectDir = projectDir(),
            agentScope = backgroundScope,
            maxFlowTicks = maxFlowTicks,
            upstreamLlmClient = client,
            eventApiFactory = { agentId -> eventApiFactory.create(agentId) },
        )

    private fun assertCompleted(outcome: ArcOutcome): ArcOutcome.Completed {
        val detail = (outcome as? ArcOutcome.Failed)?.cause?.stackTraceToString().orEmpty()
        assertIs<ArcOutcome.Completed>(outcome, "expected a completed Arc run, got $outcome $detail")
        return outcome
    }

    /** A project [ChargePhase] accepts: sources present, with a tech stack, architecture and conventions. */
    private fun projectDir() = createTempDirectory("ampr395-goal-handoff").also { dir ->
        dir.resolve("README.md").writeText(
            """
            # GoalHandoffProject

            A test project proving the Arc tick hands its goal to an agent.
            """.trimIndent(),
        )
        dir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use suspend functions

            ## Architecture
            - Layered
            """.trimIndent(),
        )
    }.toString().toPath()
}

/**
 * An [UpstreamLlmClient] that answers each cognitive phase with the JSON that phase parses, and
 * records every user prompt it was asked.
 *
 * Calls are classified by the prompt's own opening line rather than by transport metadata:
 * `PerceptionEvaluator` and `PlanGenerator` are the only producers of these two prompts, and
 * `ArcGoalHandoffTest` cross-checks the classification against the persisted `cognitivePhase`.
 *
 * @param planStepToolId `toolToUse` for every generated plan step. Null omits the field, which
 *   makes the step a tool-less reasoning step and therefore a success; a tool id no agent has
 *   fails the step critically, which keeps the goal from completing.
 */
private class ScriptedUpstreamLlmClient(
    private val planStepToolId: String?,
) : UpstreamLlmClient {

    /** Appended from the agent's IO dispatcher, read from the test thread. */
    private val prompts = CopyOnWriteArrayList<String>()

    fun planningPrompts(): List<String> = prompts.filter { isPlanningPrompt(it) }

    fun planningPromptsMentioning(goal: String): List<String> =
        planningPrompts().filter { it.contains(goal) }

    fun perceptionPromptsMentioning(goal: String): List<String> =
        prompts.filter { !isPlanningPrompt(it) && it.contains(goal) }

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        val prompt = request.messages.last { it.role == ChatRole.User }.content.orEmpty()
        prompts += prompt

        return ChatCompletion(
            id = "scripted",
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(
                        role = ChatRole.Assistant,
                        content = if (isPlanningPrompt(prompt)) planJson() else PERCEPTION_JSON,
                    ),
                ),
            ),
        )
    }

    /** The shape `PlanGenerator.parsePlanFromResponse` reads. */
    private fun planJson(): String = buildJsonObject {
        putJsonArray("steps") {
            addJsonObject {
                put("description", STEP_DESCRIPTION)
                planStepToolId?.let { put("toolToUse", it) }
                put("requiresPreviousStep", false)
            }
        }
        put("estimatedComplexity", 1)
        put("requiresHumanInput", false)
    }.toString()

    private companion object {
        /**
         * Shares no wording with the goal, so a prompt built from the step rather than from the
         * goal could never be miscounted as one carrying the goal text. Since AMPR-396 the
         * Execute phase dispatches the step it was handed instead of re-planning it, so there is
         * only one planning prompt per tick anyway — this keeps the assertion honest without
         * resting on that.
         */
        const val STEP_DESCRIPTION = "Carry out the single step"

        const val PERCEPTION_JSON =
            """[{"observation":"A task is assigned","implication":"Plan it","confidence":"high"}]"""

        /** `PlanGenerator.buildPlanningPrompt`'s opening line; the perception prompt says "perception module". */
        fun isPlanningPrompt(prompt: String): Boolean = prompt.startsWith("You are the planning module")
    }
}
