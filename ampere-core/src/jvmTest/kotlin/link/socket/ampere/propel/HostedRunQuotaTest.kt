package link.socket.ampere.propel

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.time.MutableClock

/**
 * AMPR-445 (F14): a hosted run's `RunPolicy.quotas` are Hard rules the Breaker admits each
 * step against, read from the run's persisted events.
 *
 * Drives the production wiring — `rosterRunHost` over an in-memory door — with one seat
 * whose transport reports usage only for reasoning steps, so the run's token spend after
 * step 1 is exactly what step 1 spent. Every door shares one [MutableClock], which stamps
 * `recorded_at` and is the Breaker's "now", so elapsed time moves only when a step moves it.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual
 * clock walks straight past it.
 */
class HostedRunQuotaTest {

    private lateinit var scope: CoroutineScope
    private lateinit var clock: MutableClock
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        clock = MutableClock(START)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope, clock = clock)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `step 2 trips on a token quota set to the spend after step 1 and step 3 never runs`() = runBlocking<Unit> {
        val transport = MeteredUpstreamLlmClient()

        val run = openRun(transport, RunPolicy(quotas = RunQuotas(tokens = STEP_TOKENS)))
        val result = run.execute()
        run.close()

        assertEquals(1, transport.stepCalls, "only step 1's model call was made")
        // Step 1 succeeded beside the trip, so the run did part of its work (AMPR-450).
        assertEquals(RunStatus.Partial, result.status)
        assertEquals(3, result.stepOutcomes.size)
        assertIs<StepOutcome.Success>(result.stepOutcomes[0])
        val tripped = assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
        assertTrue(tripped.error.contains("token quota"), tripped.error)
        assertTrue(tripped.error.contains("spent $STEP_TOKENS tokens"), tripped.error)
        assertTrue(tripped.error.contains("limit of $STEP_TOKENS"), tripped.error)
        assertIs<StepOutcome.Skipped>(result.stepOutcomes[2])

        // The record: the limit is the spend the run's events show after step 1, step 2's
        // completion says why it stopped, and step 3 was never started.
        val record = record(result.runId)
        val spent = record.mapNotNull { (it.event as? ProviderCallCompletedEvent)?.usage }
            .sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
        assertEquals(STEP_TOKENS, spent.toLong(), "the quota was set to what the record shows step 1 spent")

        val completed = record.mapNotNull { it.event as? PlanEvent.PlanStepCompleted }
        assertEquals(listOf(0, 1), completed.map { it.stepIndex })
        val why = assertIs<StepOutcome.Failure>(completed[1].outcome)
        assertTrue(why.error.contains("token quota"), why.error)

        val started = record.mapNotNull { it.event as? PlanEvent.PlanStepStarted }
        assertTrue(started.none { it.stepIndex == 2 }, "step 3 was never started")
    }

    @Test
    fun `step 2 trips on a wall-clock quota that step 1 used up`() = runBlocking<Unit> {
        val transport = MeteredUpstreamLlmClient(onStep = { clock.advance(10.minutes) })

        val run = openRun(transport, RunPolicy(quotas = RunQuotas(wallClock = 10.minutes)))
        val result = run.execute()
        run.close()

        assertEquals(1, transport.stepCalls)
        val tripped = assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
        assertTrue(tripped.error.contains("wall-clock quota"), tripped.error)
        assertTrue(tripped.error.contains("limit of 10m"), tripped.error)
        assertIs<StepOutcome.Skipped>(result.stepOutcomes[2])
    }

    @Test
    fun `step 2 trips on a step quota of one`() = runBlocking<Unit> {
        val transport = MeteredUpstreamLlmClient()

        val run = openRun(transport, RunPolicy(quotas = RunQuotas(steps = 1)))
        val result = run.execute()
        run.close()

        assertEquals(1, transport.stepCalls)
        val tripped = assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
        assertTrue(tripped.error.contains("step quota"), tripped.error)
        assertTrue(tripped.error.contains("executed 1 steps"), tripped.error)
    }

    @Test
    fun `a run with no quotas runs every step`() = runBlocking<Unit> {
        val transport = MeteredUpstreamLlmClient(onStep = { clock.advance(10.minutes) })

        val run = openRun(transport, RunPolicy())
        val result = run.execute()
        run.close()

        assertEquals(3, transport.stepCalls)
        assertEquals(RunStatus.Succeeded, result.status)
        assertTrue(result.stepOutcomes.all { it is StepOutcome.Success }, "${result.stepOutcomes}")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /**
     * The host seat's transport. PERCEIVE and PLAN report no usage; each reasoning step
     * reports [STEP_TOKENS] and runs [onStep], so a step is what moves the spend and the
     * clock.
     */
    private class MeteredUpstreamLlmClient(
        private val onStep: () -> Unit = {},
    ) : UpstreamLlmClient {

        private val stepPrompts = CopyOnWriteArrayList<String>()

        val stepCalls: Int get() = stepPrompts.size

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            val prompt = request.messages.joinToString("\n") { it.content.orEmpty() }
            val isStep = prompt.contains(REASONING_STEP_MARKER)
            val answer = when {
                prompt.contains("perception module") -> PERCEPTION_JSON
                isStep -> "step conclusion ${stepPrompts.size + 1}"
                else -> planJson(*STEPS.map { planStep(it, toolToUse = null, seat = HOST_ROLE.value) }.toTypedArray())
            }
            if (isStep) {
                stepPrompts += prompt
                onStep()
            }
            return ChatCompletion(
                id = generateUUID("metered-completion"),
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(index = 0, message = ChatMessage(role = ChatRole.Assistant, content = answer)),
                ),
                usage = if (isStep) {
                    Usage(
                        promptTokens = STEP_INPUT_TOKENS,
                        completionTokens = STEP_OUTPUT_TOKENS,
                        totalTokens = STEP_INPUT_TOKENS + STEP_OUTPUT_TOKENS,
                    )
                } else {
                    null
                },
            )
        }
    }

    private suspend fun openRun(transport: UpstreamLlmClient, policy: RunPolicy): HostedRun =
        rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
            roster = RosterConfig(
                host = HOST_ROLE,
                roles = listOf(RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT)),
            ),
            seats = mapOf(
                HOST_ROLE to HostedAgent(
                    id = HOST_ID,
                    role = HOST_ROLE,
                    charter = "You are the host of this run.",
                    aiConfiguration = AI_CONFIGURATION,
                    upstreamLlmClient = transport,
                ),
            ),
            goal = goal(GOAL_TEXT),
            tools = emptySet(),
            policy = policy,
        )

    /** The run's record, read the way the Breaker reads it. */
    private suspend fun record(runId: String): List<StoredEvent> =
        door.repository.getEventsByRunId(runId).getOrThrow()

    private companion object {
        const val SDK_ID = "hosted-run-quota-test-sdk"
        const val HOST_ID = "seat-host"
        const val GOAL_TEXT = "Summarise the retry policy"

        const val STEP_INPUT_TOKENS = 400
        const val STEP_OUTPUT_TOKENS = 100
        const val STEP_TOKENS: Long = 500L

        /** How `SparkBasedAgent` opens the prompt for a step that nominates no tool. */
        const val REASONING_STEP_MARKER = "You are carrying out one step of a plan"

        val STEPS = listOf("Read the retry policy", "Compare it with the spec", "Write the summary")

        val START: Instant = Instant.parse("2026-10-11T12:00:00Z")
        val HOST_ROLE = RoleId("planner")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
