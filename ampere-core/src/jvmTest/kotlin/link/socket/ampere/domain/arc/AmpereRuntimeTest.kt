package link.socket.ampere.domain.arc

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.definition.SparkBasedAgent
import okio.Path.Companion.toPath

class AmpereRuntimeTest {

    @Test
    fun `runtime executes full arc lifecycle`() = runTest {
        val tempDir = createTempDirectory("runtime-test")
        tempDir.resolve("README.md").writeText(
            """
            # TestProject

            A test project for runtime integration.
            """.trimIndent(),
        )
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin
            - Coroutines

            ## Conventions
            - Use suspend functions

            ## Architecture
            - Clean architecture
            """.trimIndent(),
        )

        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(
                ArcAgentConfig(role = "pm"),
                ArcAgentConfig(role = "code"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("pm", "code"),
            ),
        )

        val runtime = AmpereRuntime(
            arcConfig = arcConfig,
            projectDir = tempDir.toString().toPath(),
            maxFlowTicks = 5,
        )

        val result = runtime.execute("Implement user login")

        // Verify Charge phase results
        assertNotNull(result.chargeResult)
        assertEquals("TestProject", result.chargeResult.projectContext.projectId)
        assertEquals(2, result.chargeResult.agents.size)
        assertEquals("Implement user login", result.chargeResult.goalTree.root.description)

        // Verify Flow phase results
        assertNotNull(result.flowResult)
        assertTrue(result.flowResult.finalTick >= 0)
        assertTrue(result.flowResult.terminationReason in listOf(
            TerminationReason.GOAL_COMPLETE,
            TerminationReason.MAX_TICKS_REACHED,
        ))

        // Verify Pulse phase results
        assertNotNull(result.pulseResult)
        assertNotNull(result.pulseResult.evaluationReport)
        assertTrue(result.pulseResult.evaluationReport.goalsTotal > 0)
    }

    @Test
    fun `runtime charge only executes only charge phase`() = runTest {
        val tempDir = createTempDirectory("runtime-charge-only")
        tempDir.resolve("README.md").writeText(
            """
            # ChargeOnlyProject

            Testing charge-only execution.
            """.trimIndent(),
        )
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use idiomatic Kotlin

            ## Architecture
            - Modular design
            """.trimIndent(),
        )

        val arcConfig = ArcConfig(
            name = "charge-only-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
        )

        val runtime = AmpereRuntime(
            arcConfig = arcConfig,
            projectDir = tempDir.toString().toPath(),
        )

        val chargeResult = runtime.executeChargeOnly("Build API endpoint")

        assertEquals("ChargeOnlyProject", chargeResult.projectContext.projectId)
        assertEquals(1, chargeResult.agents.size)
        assertEquals("Build API endpoint", chargeResult.goalTree.root.description)
    }

    @Test
    fun `runtime from team config creates equivalent arc`() {
        val teamRoles = listOf("product-manager", "engineer", "qa-tester")
        val tempDir = createTempDirectory("runtime-team-config")

        val runtime = AmpereRuntime.fromTeamConfig(
            teamRoles = teamRoles,
            projectDir = tempDir.toString().toPath(),
        )

        val arcConfig = runtime.getArcConfig()

        assertEquals("team-config", arcConfig.name)
        assertEquals(3, arcConfig.agents.size)
        assertEquals("pm", arcConfig.agents[0].role)
        assertEquals("code", arcConfig.agents[1].role)
        assertEquals("qa", arcConfig.agents[2].role)
    }

    @Test
    fun `team config to arc config normalizes roles correctly`() {
        val testCases = mapOf(
            "product-manager" to "pm",
            "pm" to "pm",
            "engineer" to "code",
            "developer" to "code",
            "qa-tester" to "qa",
            "architect" to "planner",
            "security-reviewer" to "scanner",
            "technical-writer" to "writer",
        )

        testCases.forEach { (input, expected) ->
            val arcConfig = AmpereRuntime.teamConfigToArcConfig(listOf(input))
            assertEquals(
                expected,
                arcConfig.agents.first().role,
                "Expected $input to normalize to $expected",
            )
        }
    }

    @Test
    fun `runtime reports running state correctly`() = runTest {
        val tempDir = createTempDirectory("runtime-state")
        tempDir.resolve("README.md").writeText("# StateProject\n\nTest project.")
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use standard patterns

            ## Architecture
            - Simple structure
            """.trimIndent(),
        )

        val arcConfig = ArcConfig(
            name = "state-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
        )

        val runtime = AmpereRuntime(
            arcConfig = arcConfig,
            projectDir = tempDir.toString().toPath(),
            maxFlowTicks = 1,
        )

        assertFalse(runtime.isRunning())

        runtime.execute("Test goal")

        assertFalse(runtime.isRunning())
    }

    @Test
    fun `runtime preserves arc config`() {
        val tempDir = createTempDirectory("runtime-config")

        val arcConfig = ArcConfig(
            name = "preserved-arc",
            description = "Test description",
            agents = listOf(
                ArcAgentConfig(role = "pm", sparks = listOf("vision")),
                ArcAgentConfig(role = "code", sparks = listOf("kotlin")),
            ),
        )

        val runtime = AmpereRuntime(
            arcConfig = arcConfig,
            projectDir = tempDir.toString().toPath(),
        )

        val retrieved = runtime.getArcConfig()

        assertEquals("preserved-arc", retrieved.name)
        assertEquals("Test description", retrieved.description)
        assertEquals(2, retrieved.agents.size)
        assertEquals(listOf("vision"), retrieved.agents[0].sparks)
        assertEquals(listOf("kotlin"), retrieved.agents[1].sparks)
    }

    @Test
    fun `runtime rejects blank goal`() = runTest {
        val tempDir = createTempDirectory("runtime-blank-goal")

        val runtime = AmpereRuntime(
            arcConfig = ArcRegistry.getDefault(),
            projectDir = tempDir.toString().toPath(),
        )

        val exception = runCatching { runtime.execute("") }.exceptionOrNull()

        assertNotNull(exception)
        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception.message?.contains("blank") == true)
    }

    @Test
    fun `runtime uses registry arcs correctly`() = runTest {
        val tempDir = createTempDirectory("runtime-registry")
        tempDir.resolve("README.md").writeText("# RegistryProject\n\nTest project.")
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Docker
            - Kubernetes

            ## Conventions
            - Use GitOps patterns

            ## Architecture
            - Microservices
            """.trimIndent(),
        )

        val devopsArc = ArcRegistry.get("devops-pipeline")!!

        val runtime = AmpereRuntime(
            arcConfig = devopsArc,
            projectDir = tempDir.toString().toPath(),
            maxFlowTicks = 1,
        )

        val result = runtime.execute("Deploy to staging")

        assertEquals(3, result.chargeResult.agents.size)
        assertTrue(result.chargeResult.agents.filterIsInstance<SparkBasedAgent>().any {
            it.cognitiveState.contains("Role:Operations")
        })
    }
}
