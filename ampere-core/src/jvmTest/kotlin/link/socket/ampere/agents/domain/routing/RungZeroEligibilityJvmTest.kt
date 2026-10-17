package link.socket.ampere.agents.domain.routing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.charleskorn.kaml.Yaml
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptorSource
import link.socket.ampere.agents.domain.routing.capability.YamlModelDescriptorSource
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.propel.HostedAgent
import link.socket.ampere.propel.rosterRunHost
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig

/**
 * AMPR-459, the half of [RungZeroEligibilityTest] that needs the JVM: the two entry points
 * whose construction needs a database — [AgentFactory], whose ticket orchestrator is built
 * over one, and a hosted seat, whose event door is — and the YAML catalog source (AMPR-235)
 * and YAML-authored Arcs, which are JVM-only.
 *
 * `runBlocking`, not `runTest`, wherever a door is involved: `runTest`'s virtual clock skips
 * past a `withTimeout` while the door's IO hop is in flight.
 */
class RungZeroEligibilityJvmTest {

    private lateinit var scope: CoroutineScope
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var environmentService: EnvironmentService

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        environmentService = EnvironmentService.create(database = Database(driver), scope = scope)
    }

    @AfterTest
    fun tearDown() {
        // Cancel before closing, so nothing still in flight writes to a closed driver.
        scope.cancel()
        driver.close()
    }

    // ========================================================================
    // Paths 1 and 4 through AgentFactory
    // ========================================================================

    @Test
    fun `AgentFactory refuses a ZERO code-agent floor and an on-device configuration`() {
        val floored = factory(codeAgentMinimumRung = CapabilityRung.ZERO)
        assertFailsWith<IllegalArgumentException> { floored.create<SparkBasedAgent<*>>(AgentType.CODE) }

        val onDevice = factory(aiConfiguration = ON_DEVICE)
        AgentType.entries.forEach { type ->
            assertFailsWith<IllegalArgumentException>("$type accepted the on-device configuration") {
                onDevice.create<SparkBasedAgent<*>>(type)
            }
        }

        // The defaults still build: the code agent at THREE, on the default configuration.
        factory().create<SparkBasedAgent<*>>(AgentType.CODE)
    }

    @Test
    fun `AgentFactory refuses a catalog source that re-rates the on-device model`() {
        val factory = factory(modelDescriptorSource = YamlModelDescriptorSource(overrides(ON_DEVICE_MODEL, "ONE")))

        val refusal = assertFailsWith<IllegalArgumentException> { factory.create<SparkBasedAgent<*>>(AgentType.CODE) }

        assertContains(refusal.message.orEmpty(), ON_DEVICE_MODEL)
    }

    // ========================================================================
    // Path 4 through a hosted seat
    // ========================================================================

    @Test
    fun `a hosted seat configured with the on-device model is refused before the run charges`() =
        runBlocking<Unit> {
            val door = InMemoryEventDoor.open(agentId = "rung-zero-sdk", scope = scope)
            try {
                val transport = CountingTransport()
                val runHost = rosterRunHost(createEventApi = { id -> door.doorFor(id) }, agentScope = scope)
                val seat = HostedAgent(
                    id = "seat-host",
                    role = HOST,
                    aiConfiguration = ON_DEVICE,
                    upstreamLlmClient = transport,
                )

                val refusal = assertFailsWith<IllegalArgumentException> {
                    runHost.open(roster = ROSTER, seats = mapOf(HOST to seat), goal = GOAL)
                }

                assertContains(refusal.message.orEmpty(), "display text only")
                assertEquals(0, transport.calls.get(), "no phase was charged")
                assertTrue(runHost.openRunSeats().isEmpty(), "and nothing was left registered")
            } finally {
                door.close()
            }
        }

    // ========================================================================
    // Path 3 through the YAML catalog source
    // ========================================================================

    @Test
    fun `a YAML override that re-rates the on-device model to ONE is refused at seed and on refresh`() =
        runBlocking<Unit> {
            val source = YamlModelDescriptorSource(overrides(ON_DEVICE_MODEL, "ONE"))
            // The source reads the file as written; it is the registry that refuses what it says.
            val catalog = source.load().getOrThrow()
            assertEquals(CapabilityRung.ONE, catalog.single { it.modelName == ON_DEVICE_MODEL }.rung)

            assertFailsWith<IllegalArgumentException> { InMemoryModelDescriptorRegistry(seed = catalog) }

            val registry = InMemoryModelDescriptorRegistry(source = source)
            assertIs<IllegalArgumentException>(registry.refresh().exceptionOrNull())
            assertEquals(
                CapabilityRung.ZERO,
                registry.descriptorFor(ON_DEVICE_MODEL)?.rung,
                "the refused refresh left the bundled catalog in place",
            )

            assertTrue(InMemoryModelDescriptorRegistry.from(source).isFailure)
        }

    @Test
    fun `a YAML override may still re-rate a cloud model`() = runBlocking<Unit> {
        val cloudModel = AIModel_Gemini.Flash_2_5.name
        val source: ModelDescriptorSource = YamlModelDescriptorSource(overrides(cloudModel, "THREE"))

        val registry = assertNotNull(InMemoryModelDescriptorRegistry.from(source).getOrNull())

        assertEquals(CapabilityRung.THREE, registry.descriptorFor(cloudModel)?.rung)
    }

    // ========================================================================
    // Path 1 through a YAML-authored Arc
    // ========================================================================

    @Test
    fun `a YAML Arc whose step declares minimumRung 0 fails as it is decoded`() {
        fun arc(rung: Int) = """
            name: yaml-arc
            agents:
              - role: code
                minimumRung: $rung
        """.trimIndent()

        assertEquals(
            CapabilityRung.ONE,
            Yaml.default.decodeFromString(ArcConfig.serializer(), arc(1)).agents.single().minimumRung,
        )
        assertFailsWith<IllegalArgumentException> { Yaml.default.decodeFromString(ArcConfig.serializer(), arc(0)) }
    }

    // ========================================================================
    // Fixtures
    // ========================================================================

    private fun factory(
        codeAgentMinimumRung: CapabilityRung? = AgentFactory.DEFAULT_CODE_AGENT_RUNG,
        aiConfiguration: AIConfiguration? = null,
        modelDescriptorSource: ModelDescriptorSource? = null,
    ): AgentFactory = AgentFactory(
        scope = scope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        workspace = ExecutionWorkspace(baseDirectory = "/tmp/rung-zero-eligibility"),
        aiConfiguration = aiConfiguration,
        codeAgentMinimumRung = codeAgentMinimumRung,
        modelDescriptorSource = modelDescriptorSource,
    )

    /** A `model-overrides` file re-rating [model] to [rung], as `.ampere/model-rungs.yaml` would. */
    private fun overrides(model: String, rung: String): File =
        File.createTempFile("model-rungs", ".yaml").apply {
            deleteOnExit()
            writeText(
                """
                model-overrides:
                  $model:
                    rung: $rung
                """.trimIndent(),
            )
        }

    /** Counts the calls that reach it; a refused seat must leave it at zero. */
    private class CountingTransport : UpstreamLlmClient {
        val calls = AtomicInteger(0)

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            calls.incrementAndGet()
            error("a refused seat makes no model call")
        }
    }

    private companion object {
        val ON_DEVICE: AIConfiguration =
            AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)
        val ON_DEVICE_MODEL: String = AIModel_OnDevice.AppleFoundationModels.name

        val HOST = RoleId("planner")
        val ROSTER = RosterConfig(
            host = HOST,
            roles = listOf(
                RoleConfig(id = HOST, title = "Host", instructions = PromptRef(id = "test-prompt", version = 1)),
            ),
        )
        val GOAL = Task.Step(id = "rung-zero-goal", status = TaskStatus.Pending, description = "Plan the work")
    }
}
