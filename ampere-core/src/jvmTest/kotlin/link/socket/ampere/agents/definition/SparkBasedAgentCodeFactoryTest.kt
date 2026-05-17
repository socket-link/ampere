package link.socket.ampere.agents.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.AmpereSpikeFlags
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.cognition.sparks.SparkSelectionContext
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.planning.PLAN_STEPS_TOOL_ID
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider

/**
 * Verifies the `SparkBasedAgent.Code(...)` factory produces an agent shaped
 * like the legacy `CodeAgent`: ANALYTICAL affinity, `plan_steps` plus any
 * caller-supplied tools, and a spark stack with `Role:Code` already on
 * top of the affinity. Library wiring (for `code-agent.spark.md`) is a
 * separate concern — exercised here via the internal setter.
 */
class SparkBasedAgentCodeFactoryTest {

    private val phaseSparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("provider should not be invoked in factory test")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    @Test
    fun `factory builds an analytical code agent with the role spark on top`() {
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "code-factory-test",
            aiConfiguration = FakeAIConfiguration(),
        )

        assertEquals(CognitiveAffinity.ANALYTICAL, agent.affinity)
        // Role spark id is "code"; declarative fixture's name field is "Role:Code"
        // (matching the retired Kotlin singleton's name).
        assertTrue(
            agent.cognitiveState.endsWith("[Role:Code]"),
            "declarative role-code spark should be the most recently applied spark; got: ${agent.cognitiveState}",
        )
    }

    @Test
    fun `factory fails fast when the library has no role-code fixture`() {
        // Library loaded with a single non-role fixture: lookup must fail loudly
        // rather than silently fall back to a Kotlin singleton.
        val emptyLibrary: PhaseSparkLibrary = runBlocking {
            DefaultPhaseSparkLibrary.load(sparkResourcePaths = listOf("files/sparks/minimal-edge.spark.md"))
        }

        val failure = assertFailsWith<IllegalStateException> {
            SparkBasedAgent.Code(
                sparkRegistry = emptyLibrary,
                agentId = "code-factory-no-library-test",
                aiConfiguration = FakeAIConfiguration(),
            )
        }
        assertTrue(
            failure.message?.contains("role-code") == true,
            "error should name the missing fixture; got: ${failure.message}",
        )
    }

    @Test
    fun `factory includes plan_steps and any caller-supplied tools`() {
        val noopTool = FunctionTool<ExecutionContext.NoChanges>(
            id = "noop_for_test",
            name = "Noop",
            description = "test placeholder",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { error("not used in this test") },
        )
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "code-factory-test-tools",
            aiConfiguration = FakeAIConfiguration(),
            tools = setOf(noopTool),
        )

        val toolIds = agent.requiredTools.map { it.id }.toSet()
        assertTrue(PLAN_STEPS_TOOL_ID in toolIds, "plan_steps should be present by default; got $toolIds")
        assertTrue("noop_for_test" in toolIds, "caller-supplied tools should be present; got $toolIds")
    }

    @Test
    fun `factory exposes the canonical code-agent spark id constant`() {
        assertEquals("code-agent", SparkBasedAgent.CODE_AGENT_SPARK_ID)
    }

    @Test
    fun `product and project factories use the declarative planning role spark`() {
        val product = SparkBasedAgent.Product(
            sparkRegistry = phaseSparkLibrary,
            agentId = "product-factory-test",
            aiConfiguration = FakeAIConfiguration(),
        )
        val project = SparkBasedAgent.Project(
            sparkRegistry = phaseSparkLibrary,
            agentId = "project-factory-test",
            aiConfiguration = FakeAIConfiguration(),
        )

        assertEquals(CognitiveAffinity.INTEGRATIVE, product.affinity)
        assertEquals(CognitiveAffinity.INTEGRATIVE, project.affinity)
        assertTrue(
            product.cognitiveState.endsWith("[Role:Planning]"),
            "product factory should stack role-planning; got: ${product.cognitiveState}",
        )
        assertTrue(
            project.cognitiveState.endsWith("[Role:Planning]"),
            "project factory should stack role-planning; got: ${project.cognitiveState}",
        )
    }

    @Test
    fun `product and project factories fail fast when planning role fixture is missing`() {
        val emptyLibrary: PhaseSparkLibrary = runBlocking {
            DefaultPhaseSparkLibrary.load(sparkResourcePaths = listOf("files/sparks/minimal-edge.spark.md"))
        }

        val productFailure = assertFailsWith<IllegalStateException> {
            SparkBasedAgent.Product(
                sparkRegistry = emptyLibrary,
                agentId = "product-factory-no-library-test",
                aiConfiguration = FakeAIConfiguration(),
            )
        }
        assertTrue(
            productFailure.message?.contains("role-planning") == true,
            "error should name the missing fixture; got: ${productFailure.message}",
        )

        val projectFailure = assertFailsWith<IllegalStateException> {
            SparkBasedAgent.Project(
                sparkRegistry = emptyLibrary,
                agentId = "project-factory-no-library-test",
                aiConfiguration = FakeAIConfiguration(),
            )
        }
        assertTrue(
            projectFailure.message?.contains("role-planning") == true,
            "error should name the missing fixture; got: ${projectFailure.message}",
        )
    }

    @Test
    fun `wiring a PhaseSparkLibrary lets the code-agent declarative spark activate during a phase`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()
        val agent = SparkBasedAgent.Code(
            sparkRegistry = library,
            agentId = "code-factory-test-library",
            aiConfiguration = FakeAIConfiguration(),
        )
        agent.setPhaseSparkLibrary(library)

        // The code-agent spark must be in the loaded library.
        val codeAgentSpark = library.byId(SparkBasedAgent.CODE_AGENT_SPARK_ID)
        assertNotNull(codeAgentSpark, "code-agent.spark.md should be in the bundled library")

        // Drive a phase entry through the manager so the declarative spark
        // is applied to the agent's stack; assert it's then present.
        val manager = PhaseSparkManager.internalCreate(
            agent = agent,
            enabled = true,
            library = library,
        )
        val previousFlag = AmpereSpikeFlags.declarativeSparksEnabled
        AmpereSpikeFlags.declarativeSparksEnabled = true
        try {
            manager.enterPhase(
                phase = CognitivePhase.PLAN,
                selectionContext = SparkSelectionContext(
                    phase = CognitivePhase.PLAN,
                    // Words chosen to overlap the code-agent spark's whenToUse
                    // text so the library actually selects it.
                    text = "write code in the workspace to commit changes",
                ),
            )
            val systemPrompt = agent.currentSystemPrompt
            assertTrue(
                systemPrompt.contains("plan_steps"),
                "expected the code-agent spark's PLAN guidance (which references plan_steps) " +
                    "in the active system prompt, got: $systemPrompt",
            )
        } finally {
            AmpereSpikeFlags.declarativeSparksEnabled = previousFlag
            manager.cleanup()
        }
    }
}
