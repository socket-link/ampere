package link.socket.ampere.api

import app.cash.sqldelight.db.SqlDriver
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.data.createIosDriver
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.propel.HostedAgent
import link.socket.ampere.propel.RunPolicy
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RosterConfig

/**
 * iOS construction smoke test for [Ampere.fromEnvironment].
 *
 * Exercises [createIosDriver] —
 * [NativeSqliteDriver][app.cash.sqldelight.driver.native.NativeSqliteDriver]
 * end-to-end on the iOS simulator — proving the migrated `fromEnvironment`
 * extension and its `Default*Service` dependencies compile and execute on
 * iOS Native.
 *
 * Runs as part of `iosSimulatorArm64Test` in CI on the macOS runner.
 *
 * The hosted-run test is task 6 of AMPR-393: every public parameter of a run is
 * constructible on this target, and the run is driveable from it. iOS is the strict case —
 * [link.socket.ampere.roster.RoleId] is an inline value class the Objective-C export erases
 * to `Any?`, so a Swift caller cannot write one and every type a consumer authors needs a
 * plain-id factory (`RoleConfig.of`, `RosterConfig.of`, `HostedAgent.of`,
 * `RunHost.openSeats`). This test builds a run *only* through those, so a parameter that
 * loses its factory fails here rather than in the consumer's Xcode build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmpereFromEnvironmentIosTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var environmentService: EnvironmentService
    private lateinit var knowledgeRepository: KnowledgeRepository

    @BeforeTest
    fun setUp() {
        driver = createIosDriver(dbName = "ampere-ios-test.db")
        database = Database(driver)
        environmentService = EnvironmentService.create(database = database, scope = scope)
        knowledgeRepository = KnowledgeRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `fromEnvironment constructs on iOS with NativeSqliteDriver`() {
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            workspace = "/tmp/ampr300-test-workspace",
        )

        assertNotNull(instance.agents)
        assertNotNull(instance.tickets)
        assertNotNull(instance.threads)
        assertNotNull(instance.events)
        assertNotNull(instance.outcomes)
        assertNotNull(instance.pricing)
        assertNotNull(instance.knowledge)
        assertNotNull(instance.status)

        instance.close()
    }

    @Test
    fun `fromEnvironment hosts a run on iOS built only from plain-id factories`() = runBlocking {
        environmentService.start()

        val store = memoryStoreOf(
            knowledge = KnowledgeRepositoryImpl(database),
            outcomes = OutcomeMemoryRepositoryImpl(database),
        )

        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            workspace = "/tmp/ampr300-test-workspace",
            memoryStore = store,
            upstreamLlmClient = FixedUpstream(PLAN_JSON),
        )
        val runs = assertNotNull(instance.runs, "fromEnvironment can host (AMPR-393 row H9)")

        // Everything below goes through a plain-id factory: no RoleId, no Map<RoleId, …>.
        val roster = RosterConfig.of(
            host = HOST_ROLE,
            roles = listOf(
                RoleConfig.of(
                    id = HOST_ROLE,
                    title = "Host",
                    instructions = PromptRef(id = "ios-host-prompt", version = 1),
                ),
            ),
        )
        val seat = HostedAgent.of(
            id = "ios-seat-host",
            role = HOST_ROLE,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_5,
            ),
            upstreamLlmClient = FixedUpstream(PLAN_JSON),
            charter = "You are the host of this run.",
            memory = store,
        )

        val run = runs.openSeats(
            roster = roster,
            seats = listOf(seat),
            goal = Task.Step(
                id = "ios-goal",
                status = TaskStatus.Pending,
                description = "decide what to do next",
            ),
            policy = RunPolicy(cycles = 1),
        )
        assertTrue(run.plan.tasks.isNotEmpty(), "the host seat planned on iOS")

        val result = run.execute()
        run.close()

        assertEquals(1, result.cyclesUsed)
        assertTrue(runs.openRunSeats().isEmpty(), "a closed run is not an open run")

        val now = kotlinx.datetime.Clock.System.now()
        val events = instance.events.query(
            fromTime = now - kotlin.time.Duration.parse("PT1M"),
            toTime = now + kotlin.time.Duration.parse("PT1M"),
        ).getOrThrow()
        assertTrue(
            events.any { it is Event.TaskCreated && it.taskId == run.runId },
            "the run's task is on the record under the run id",
        )
        assertTrue(
            events.any { it is CognitivePhaseEvent.PhaseEntered },
            "and so are its phase brackets",
        )

        instance.close()
    }

    private companion object {
        const val HOST_ROLE = "planner"

        /** One reasoning step, as `PlanGenerator` parses it. */
        val PLAN_JSON = """
        {
          "steps": [
            { "description": "Decide what to do next", "toolToUse": null, "seat": "planner" }
          ],
          "estimatedComplexity": 1,
          "requiresHumanInput": false
        }
        """
    }
}

/**
 * A transport that answers the same text to everything.
 *
 * `PerceptionEvaluator` and the reasoning step both tolerate text they cannot parse —
 * the first falls back to an idea, the second takes it as the step's conclusion — so one
 * canned plan is enough to drive a one-cycle run.
 */
private class FixedUpstream(private val answer: String) : UpstreamLlmClient {
    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion = ChatCompletion(
        id = "ios-fixed",
        created = 0L,
        model = ModelId(configuration.model.name),
        choices = listOf(
            ChatChoice(
                index = 0,
                message = ChatMessage(role = ChatRole.Assistant, content = answer),
            ),
        ),
    )
}
