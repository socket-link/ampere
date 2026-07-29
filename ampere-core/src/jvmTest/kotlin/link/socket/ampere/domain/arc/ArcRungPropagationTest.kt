package link.socket.ampere.domain.arc

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.charleskorn.kaml.Yaml
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import link.socket.ampere.agents.definition.SparkAgentFactory
import link.socket.ampere.agents.domain.routing.RoutingFloorUnmetException
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.llm.UpstreamLlmClient
import okio.Path.Companion.toPath

/**
 * AMPR-232: rung declaration on the Arc path.
 *
 * Before this ticket the whole Arc path was dormant w.r.t. rungs — `ArcAgentConfig`
 * carried no floor and `SparkAgentFactory.createAgent` built agents without one, so
 * an Arc could not express "this step needs a capable model". These tests cover the
 * declaration (`ArcConfig`/`ArcAgentConfig`), its composition
 * ([minimumRungFor]), its propagation into the spawned agent, and the enforcement
 * that makes a declared floor mean something rather than being silently ignored.
 */
class ArcRungPropagationTest {

    // The agent's static fallback. If the relay ever resolved to this on a
    // floored step, the floor would have been ignored — which is the bug.
    private val agentFallback = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)

    private fun projectContext(): ProjectContext {
        val projectRoot = createTempDirectory("arc-rung").toString().toPath()
        return ProjectContext(
            projectId = "demo",
            description = "Demo project",
            repositoryRoot = projectRoot,
            architecture = "Layered",
            conventions = "Use Kotlin",
            techStack = listOf("Kotlin"),
            sources = listOf(ProjectContextSource(projectRoot / "README.md", "Demo")),
        )
    }

    // ========================================================================
    // Declaration + composition
    // ========================================================================

    @Test
    fun `a step declaring no floor and an Arc declaring none leaves the step unconstrained`() {
        val arc = ArcConfig(name = "arc", agents = listOf(ArcAgentConfig(role = "code")))

        assertNull(arc.minimumRungFor(arc.agents[0]))
    }

    @Test
    fun `a step floor applies when the Arc declares none`() {
        val step = ArcAgentConfig(role = "code", minimumRung = CapabilityRung.THREE)
        val arc = ArcConfig(name = "arc", agents = listOf(step))

        assertEquals(CapabilityRung.THREE, arc.minimumRungFor(step))
    }

    @Test
    fun `the Arc-wide floor applies to a step declaring none`() {
        val step = ArcAgentConfig(role = "code")
        val arc = ArcConfig(name = "arc", agents = listOf(step), minimumRung = CapabilityRung.THREE)

        assertEquals(CapabilityRung.THREE, arc.minimumRungFor(step))
    }

    @Test
    fun `a step may raise the Arc-wide floor`() {
        val step = ArcAgentConfig(role = "code", minimumRung = CapabilityRung.FOUR)
        val arc = ArcConfig(name = "arc", agents = listOf(step), minimumRung = CapabilityRung.TWO)

        assertEquals(CapabilityRung.FOUR, arc.minimumRungFor(step))
    }

    @Test
    fun `a step may not lower the Arc-wide floor`() {
        val step = ArcAgentConfig(role = "code", minimumRung = CapabilityRung.TWO)
        val arc = ArcConfig(name = "arc", agents = listOf(step), minimumRung = CapabilityRung.FOUR)

        assertEquals(CapabilityRung.FOUR, arc.minimumRungFor(step))
    }

    @Test
    fun `rung floors survive a YAML round trip`() {
        val arc = ArcConfig(
            name = "yaml-arc",
            agents = listOf(
                ArcAgentConfig(role = "code", minimumRung = CapabilityRung.FOUR),
                ArcAgentConfig(role = "pm"),
            ),
            minimumRung = CapabilityRung.TWO,
        )

        val decoded = Yaml.default.decodeFromString(
            ArcConfig.serializer(),
            Yaml.default.encodeToString(arc),
        )

        assertEquals(arc, decoded)
        assertEquals(CapabilityRung.TWO, decoded.minimumRung)
        assertEquals(CapabilityRung.FOUR, decoded.agents[0].minimumRung)
        assertNull(decoded.agents[1].minimumRung)
    }

    @Test
    fun `a YAML Arc omitting rungs decodes to no floor`() {
        val yaml = """
            name: legacy-arc
            agents:
              - role: code
                sparks:
                  - kotlin
        """.trimIndent()

        val decoded = Yaml.default.decodeFromString(ArcConfig.serializer(), yaml)

        assertNull(decoded.minimumRung)
        assertNull(decoded.agents[0].minimumRung)
    }

    // ========================================================================
    // Propagation into the spawned agent
    // ========================================================================

    @Test
    fun `the spawner threads each step's floor onto the spawned agent`() {
        val arc = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "code", minimumRung = CapabilityRung.THREE),
                ArcAgentConfig(role = "pm"),
            ),
        )

        val agents = ArcAgentSpawner().spawn(arc, projectContext())

        assertEquals(
            CapabilityRung.THREE,
            agents[0].agentConfiguration.agentDefinition.minimumRung,
            "a step declaring a floor must carry it onto the agent it spawns",
        )
        assertNull(
            agents[1].agentConfiguration.agentDefinition.minimumRung,
            "a step declaring no floor stays unconstrained",
        )
    }

    @Test
    fun `the spawner raises a step floor to the stricter Arc-wide one`() {
        val arc = ArcConfig(
            name = "arc",
            agents = listOf(ArcAgentConfig(role = "code", minimumRung = CapabilityRung.ONE)),
            minimumRung = CapabilityRung.THREE,
        )

        val agents = ArcAgentSpawner().spawn(arc, projectContext())

        assertEquals(CapabilityRung.THREE, agents[0].agentConfiguration.agentDefinition.minimumRung)
    }

    @Test
    fun `a floored Arc step gets a relay so the floor is enforced rather than ignored`() {
        val arc = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "code", minimumRung = CapabilityRung.THREE),
                ArcAgentConfig(role = "pm"),
            ),
        )

        val agents = ArcAgentSpawner().spawn(arc, projectContext())

        assertNotNull(
            agents[0].agentConfiguration.cognitiveRelay,
            "a declared floor is enforced by the relay; without one it would be silently ignored",
        )
        assertNull(
            agents[1].agentConfiguration.cognitiveRelay,
            "a floorless step keeps the pre-existing dormant behavior",
        )
    }

    // ========================================================================
    // End-to-end enforcement
    // ========================================================================

    @Test
    fun `an Arc step declaring a floor routes to a model that clears it`() {
        val client = RecordingUpstreamClient("arc-response")
        val arc = ArcConfig(
            name = "arc",
            agents = listOf(ArcAgentConfig(role = "code", minimumRung = CapabilityRung.THREE)),
        )

        val agent = ArcAgentSpawner(
            agentFactory = SparkAgentFactory(
                defaultAiConfiguration = agentFallback,
                upstreamLlmClient = client,
            ),
        ).spawn(arc, projectContext()).single()

        assertEquals("arc-response", agent.callLLM("Implement the feature."))

        // The cheapest catalog model clearing THREE — Gemini 2.5 Pro — not the
        // agent's own static fallback (GPT-4.1), which the relay would have used
        // had the floor never reached it.
        val selected = client.lastConfig.get()
        assertNotNull(selected, "the outbound client must have been called with a resolved config")
        assertEquals(AIModel_Gemini.Pro_2_5.name, selected.model.name)
    }

    @Test
    fun `an Arc step whose floor no model meets fails rather than routing lower`() {
        val client = RecordingUpstreamClient("must-not-be-called")
        // Above anything in the bundled catalog, which tops out at FOUR.
        val unsatisfiableFloor = CapabilityRung(CapabilityRung.FOUR.ordinal + 1)
        val arc = ArcConfig(
            name = "arc",
            agents = listOf(ArcAgentConfig(role = "code", minimumRung = unsatisfiableFloor)),
        )

        val agent = ArcAgentSpawner(
            agentFactory = SparkAgentFactory(
                defaultAiConfiguration = agentFallback,
                upstreamLlmClient = client,
            ),
        ).spawn(arc, projectContext()).single()

        val failure = assertFailsWith<RoutingFloorUnmetException> {
            agent.callLLM("Implement the feature.")
        }
        assertEquals(unsatisfiableFloor, failure.requestedFloor)
        assertEquals(CapabilityRung.FOUR, failure.bestAvailableRung)
        assertNull(client.lastConfig.get(), "an unmet floor must not fall through to any model")
    }

    @Test
    fun `an Arc step declaring no floor still routes through its static configuration`() {
        val client = RecordingUpstreamClient("arc-response")
        val arc = ArcConfig(name = "arc", agents = listOf(ArcAgentConfig(role = "code")))

        val agent = ArcAgentSpawner(
            agentFactory = SparkAgentFactory(
                defaultAiConfiguration = agentFallback,
                upstreamLlmClient = client,
            ),
        ).spawn(arc, projectContext()).single()

        assertEquals("arc-response", agent.callLLM("Implement the feature."))
        assertEquals(agentFallback.model.name, client.lastConfig.get()?.model?.name)
    }

    private class RecordingUpstreamClient(
        private val cannedResponse: String,
    ) : UpstreamLlmClient {
        val lastConfig = AtomicReference<AIConfiguration?>(null)

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            lastConfig.set(configuration)
            return ChatCompletion(
                id = "rec",
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(role = ChatRole.Assistant, content = cannedResponse),
                    ),
                ),
            )
        }
    }
}
