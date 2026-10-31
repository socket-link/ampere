package link.socket.ampere.propel

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.AdmissionRecord
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Rule
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.time.MutableClock

/**
 * AMPR-512: `RunPolicy.rules` are admitted on every step beside the run's quotas, after them,
 * and a trip on one is an ordinary step failure; a kind the Breaker cannot evaluate is refused
 * at `open`.
 *
 * Drives the production wiring — `rosterRunHost` over an in-memory door — with one seat whose
 * plan is three independent reasoning steps, each reporting [STEP_TOKENS] of usage, so a token
 * rule set to [STEP_TOKENS] holds for step 1 and breaches from step 2 on.
 *
 * Every rule kind this build can evaluate and that a reasoning step can breach is a quota, and
 * a spend never goes back down, so a Hard consumer rule that breaches on step 2 breaches on
 * step 3 as well. What the Hard case pins is therefore that step 3 is still dispatched and
 * admitted on its own — a `StepAdmitted` of its own and a failure of its own — where the same
 * rule set as a run quota skips it unadmitted.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
class HostedRunConsumerRulesTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope, clock = MutableClock(START))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `a Hard consumer rule trips step 2 and step 3 is still dispatched and admitted on its own`() =
        runBlocking<Unit> {
            val transport = MeteredUpstreamLlmClient()
            val policy = RunPolicy(rules = listOf(RunRule(TOKEN_RULE, Hardness.HARD, origin = "principal")))

            val run = openRun(transport, policy)
            val result = run.execute()
            run.close()

            assertEquals(1, transport.stepCalls, "only step 1's model call was made")
            assertEquals(RunStatus.Partial, result.status)
            assertEquals(3, result.stepOutcomes.size)
            assertIs<StepOutcome.Success>(result.stepOutcomes[0])
            listOf(1, 2).forEach { index ->
                val failed = assertIs<StepOutcome.Failure>(result.stepOutcomes[index])
                assertEquals(TOKEN_RULE_NAME, failed.tripped?.rule, "step ${index + 1}: ${failed.error}")
            }

            val record = record(result.runId)
            val admitted = record.events<PlanEvent.StepAdmitted>().map { it.admission }
            assertEquals(3, admitted.size, "every step was admitted: $admitted")
            assertEquals(AdmissionRecord.Pass, admitted[0])
            admitted.drop(1).forEach { admission ->
                assertEquals(TOKEN_RULE_NAME, assertIs<AdmissionRecord.Trip>(admission).rule)
            }
            val started = record.events<PlanEvent.PlanStepStarted>().map { it.stepIndex }
            assertEquals(listOf(0, 1, 2), started, "a consumer rule's trip is not critical: the walk reaches step 3")
        }

    @Test
    fun `a Soft consumer rule resists and every step runs`() = runBlocking<Unit> {
        val transport = MeteredUpstreamLlmClient()
        val policy = RunPolicy(rules = listOf(RunRule(TOKEN_RULE, Hardness.SOFT, origin = "group")))

        val run = openRun(transport, policy)
        val result = run.execute()
        run.close()

        assertEquals(3, transport.stepCalls)
        assertEquals(RunStatus.Succeeded, result.status)
        assertTrue(result.stepOutcomes.all { it is StepOutcome.Success }, "${result.stepOutcomes}")
        val resisted = AdmissionRecord.Resist(degree = 1.0, rules = listOf(TOKEN_RULE_NAME))
        assertEquals(
            listOf(AdmissionRecord.Pass, resisted, resisted),
            record(result.runId).events<PlanEvent.StepAdmitted>().map { it.admission },
        )
    }

    @Test
    fun `consumer rules are admitted after the quotas, and a reached quota still stops the walk`() =
        runBlocking<Unit> {
            val transport = MeteredUpstreamLlmClient()
            val policy = RunPolicy(
                quotas = RunQuotas(tokens = STEP_TOKENS),
                rules = listOf(
                    RunRule(Rule.Quota.Count(limit = 1, of = RunQuotas.STEPS), Hardness.HARD, origin = "principal"),
                ),
            )

            val run = openRun(transport, policy)
            val result = run.execute()
            run.close()

            // Both breach on step 2. The quota is folded first, so it is the rule the trip names.
            val tripped = assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
            assertEquals(TOKEN_RULE_NAME, tripped.tripped?.rule, tripped.error)
            assertIs<StepOutcome.Skipped>(result.stepOutcomes[2], "a reached run quota stays critical")
            assertEquals(RunStatus.Partial, result.status)

            val record = record(result.runId)
            assertEquals(2, record.events<PlanEvent.StepAdmitted>().size, "step 3 was never admitted")
        }

    @Test
    fun `a rule kind the Breaker cannot evaluate is refused at open, naming it and its origin`() =
        runBlocking<Unit> {
            val transport = MeteredUpstreamLlmClient()
            val host = rosterRunHost(createEventApi = door::doorFor, agentScope = scope)
            val policy = RunPolicy(
                rules = listOf(
                    RunRule(TOKEN_RULE, Hardness.HARD, origin = "principal"),
                    RunRule(Rule.Deadline(due = START), Hardness.HARD, origin = "quiet-hours"),
                ),
            )

            val refused = assertFailsWith<IllegalArgumentException> { openRun(transport, policy, host) }

            val message = refused.message.orEmpty()
            assertTrue(message.contains("Deadline"), message)
            assertTrue(message.contains("quiet-hours"), message)
            assertTrue(host.openRunSeats().isEmpty(), "no run is registered")
            assertEquals(0, transport.calls, "nothing was charged")
        }

    // ========================================================================
    // Fixture
    // ========================================================================

    /**
     * The host seat's transport. PERCEIVE and PLAN report no usage; each reasoning step
     * reports [STEP_TOKENS], so after step `n` the run has spent `n * STEP_TOKENS`.
     */
    private class MeteredUpstreamLlmClient : UpstreamLlmClient {

        private val stepPrompts = CopyOnWriteArrayList<String>()
        private val callCount = AtomicInteger()

        val stepCalls: Int get() = stepPrompts.size
        val calls: Int get() = callCount.get()

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            callCount.incrementAndGet()
            val prompt = request.messages.joinToString("\n") { it.content.orEmpty() }
            val isStep = prompt.contains(REASONING_STEP_MARKER)
            val answer = when {
                prompt.contains("perception module") -> PERCEPTION_JSON
                isStep -> "step conclusion ${stepPrompts.size + 1}"
                else -> planJson(*STEPS.map { planStep(it, toolToUse = null, seat = HOST_ROLE.value) }.toTypedArray())
            }
            if (isStep) stepPrompts += prompt
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

    private suspend fun openRun(
        transport: UpstreamLlmClient,
        policy: RunPolicy,
        host: RunHost = rosterRunHost(createEventApi = door::doorFor, agentScope = scope),
    ): HostedRun = host.open(
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

    private inline fun <reified T> List<StoredEvent>.events(): List<T> = mapNotNull { it.event as? T }

    private companion object {
        const val SDK_ID = "hosted-run-consumer-rules-test-sdk"
        const val HOST_ID = "seat-host"
        const val GOAL_TEXT = "Summarise the retry policy"

        const val STEP_INPUT_TOKENS = 400
        const val STEP_OUTPUT_TOKENS = 100
        const val STEP_TOKENS: Long = 500L

        /** A consumer's token rule that holds for step 1 and breaches once step 1 has spent. */
        val TOKEN_RULE = Rule.Quota.Tokens(limit = STEP_TOKENS)
        const val TOKEN_RULE_NAME = "Quota.Tokens"

        /** How `SparkBasedAgent` opens the prompt for a step that nominates no tool. */
        const val REASONING_STEP_MARKER = "You are carrying out one step of a plan"

        val STEPS = listOf("Read the retry policy", "Compare it with the spec", "Write the summary")

        val START: Instant = Instant.parse("2026-10-30T12:00:00Z")
        val HOST_ROLE = RoleId("planner")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
