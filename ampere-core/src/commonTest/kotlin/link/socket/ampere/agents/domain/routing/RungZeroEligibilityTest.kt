package link.socket.ampere.agents.domain.routing

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.SparkAgentFactory
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptor
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptorSource
import link.socket.ampere.agents.domain.routing.capability.executesLocally
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.configuration.AIConfiguration_WithBackups
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.llm.OnDeviceInferenceBinding
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.ModelBackedDecisionClient
import link.socket.ampere.llm.decide.Question

/**
 * AMPR-459: no call made by an agent that runs the PROPEL loop, and no model-backed
 * judgment, resolves to a model that executes on the device. Rung ZERO serves display
 * text only (AMPR-372), and these are the five ways such work reached the device, one
 * section each: a `ZERO` floor on an Arc or an agent, a per-call requirement with no
 * floor, a re-rated on-device model, an on-device configuration, and the Decide adapter.
 *
 * Every closure is a raise or a refusal, and a missing floor stays legal: the tests that
 * pass no requirement pin that it still forwards none, which is what keeps a floorless
 * agent on its configured model. The entry points that need a database (`AgentFactory`,
 * a hosted seat) and the YAML catalog source are in `RungZeroEligibilityJvmTest`.
 */
class RungZeroEligibilityTest {

    // ========================================================================
    // Path 1: a floor on an Arc manifest
    // ========================================================================

    @Test
    fun `an Arc step floored at ZERO is refused where it is written`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            ArcAgentConfig(role = "code", minimumRung = CapabilityRung.ZERO)
        }

        assertNamesTheRule(refusal)
        assertContains(refusal.message.orEmpty(), "Arc step 'code'")
    }

    @Test
    fun `an Arc floored at ZERO is refused where it is written`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            ArcConfig(
                name = "display-arc",
                agents = listOf(ArcAgentConfig(role = "code")),
                minimumRung = CapabilityRung.ZERO,
            )
        }

        assertNamesTheRule(refusal)
        assertContains(refusal.message.orEmpty(), "Arc 'display-arc'")
    }

    @Test
    fun `an unnamed rung below ZERO is refused too`() {
        assertFailsWith<IllegalArgumentException> {
            ArcAgentConfig(role = "code", minimumRung = CapabilityRung(-1))
        }
    }

    @Test
    fun `a missing floor and a floor of ONE stay legal on an Arc`() {
        val arc = ArcConfig(
            name = "loop-arc",
            agents = listOf(
                ArcAgentConfig(role = "code"),
                ArcAgentConfig(role = "pm", minimumRung = CapabilityRung.ONE),
            ),
            minimumRung = CapabilityRung.ONE,
        )

        assertNull(arc.agents[0].minimumRung)
        assertEquals(CapabilityRung.ONE, arc.agents[1].minimumRung)
    }

    @Test
    fun `decoding an Arc whose minimumRung is 0 fails as it is decoded`() {
        fun stepFloored(rung: Int) = """{"name": "decoded-arc", "agents": [{"role": "code", "minimumRung": $rung}]}"""
        fun arcFloored(rung: Int) = """{"name": "decoded-arc", "agents": [{"role": "code"}], "minimumRung": $rung}"""

        // The same manifests decode at ONE, so the floor is what is refused and nothing else.
        val step = Json.decodeFromString(ArcConfig.serializer(), stepFloored(1)).agents.single()
        assertEquals(CapabilityRung.ONE, step.minimumRung)
        assertEquals(CapabilityRung.ONE, Json.decodeFromString(ArcConfig.serializer(), arcFloored(1)).minimumRung)

        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(ArcConfig.serializer(), stepFloored(0)) }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(ArcConfig.serializer(), arcFloored(0)) }
    }

    // ========================================================================
    // Paths 1 and 4: an agent's floor and its configuration
    // ========================================================================

    @Test
    fun `a SparkBasedAgent floored at ZERO is refused`() {
        val refusal = assertFailsWith<IllegalArgumentException> { agent(minimumRung = CapabilityRung.ZERO) }

        assertNamesTheRule(refusal)
    }

    @Test
    fun `a SparkBasedAgent configured with the on-device model is refused`() {
        val refusal = assertFailsWith<IllegalArgumentException> { agent(configuration = ON_DEVICE) }

        assertNamesTheRule(refusal)
        assertContains(refusal.message.orEmpty(), ON_DEVICE_MODEL)
    }

    @Test
    fun `the on-device model is refused under another provider and as a backup`() {
        // The dispatching client keys on the model, so the device runs it whatever provider names it.
        assertFailsWith<IllegalArgumentException> {
            agent(configuration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_OnDevice.AppleFoundationModels))
        }
        assertFailsWith<IllegalArgumentException> {
            agent(configuration = AIConfiguration_WithBackups(listOf(CLOUD, ON_DEVICE)))
        }
    }

    @Test
    fun `a floor of ONE or none with a cloud configuration builds an agent`() {
        agent(minimumRung = CapabilityRung.ONE, configuration = CLOUD)
        agent(minimumRung = null, configuration = CLOUD)
        // No configuration at all is the default one, which is never on the device.
        agent(minimumRung = null, configuration = null)
    }

    @Test
    fun `every role factory refuses the on-device configuration and the code factory a ZERO floor`() = runTest {
        val sparks = DefaultPhaseSparkLibrary.load()
        fun build(factory: String, configuration: AIConfiguration): SparkBasedAgent<*> = when (factory) {
            "Code" -> SparkBasedAgent.Code(sparkRegistry = sparks, aiConfiguration = configuration)
            "Product" -> SparkBasedAgent.Product(sparkRegistry = sparks, aiConfiguration = configuration)
            "Project" -> SparkBasedAgent.Project(sparkRegistry = sparks, aiConfiguration = configuration)
            "Quality" -> SparkBasedAgent.Quality(sparkRegistry = sparks, aiConfiguration = configuration)
            else -> error("no role factory named $factory")
        }

        listOf("Code", "Product", "Project", "Quality").forEach { factory ->
            build(factory, CLOUD)
            assertFailsWith<IllegalArgumentException>("$factory accepted the on-device configuration") {
                build(factory, ON_DEVICE)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SparkBasedAgent.Code(sparkRegistry = sparks, aiConfiguration = CLOUD, minimumRung = CapabilityRung.ZERO)
        }
    }

    @Test
    fun `SparkAgentFactory refuses a ZERO floor and an on-device default configuration`() {
        val scope = CoroutineScope(Job())
        try {
            val factory = SparkAgentFactory(scope = scope, workspace = WORKSPACE)
            factory.createAgent(
                id = "floored-one",
                affinity = CognitiveAffinity.ANALYTICAL,
                minimumRung = CapabilityRung.ONE,
            )
            assertFailsWith<IllegalArgumentException> {
                factory.createAgent(
                    id = "floored-zero",
                    affinity = CognitiveAffinity.ANALYTICAL,
                    minimumRung = CapabilityRung.ZERO,
                )
            }

            val onDeviceFactory = SparkAgentFactory(
                scope = scope,
                workspace = WORKSPACE,
                defaultAiConfiguration = ON_DEVICE,
            )
            assertFailsWith<IllegalArgumentException> {
                onDeviceFactory.createAgent(id = "on-device", affinity = CognitiveAffinity.ANALYTICAL)
            }
        } finally {
            scope.cancel()
        }
    }

    // ========================================================================
    // Path 2: a per-call requirement
    // ========================================================================

    @Test
    fun `a forwarded requirement arrives at ONE or above and no requirement stays none`() = runTest {
        val relay = RecordingRelay()
        val reasoning = AgentReasoning.create(
            config = configuration(relay = relay, transport = AnsweringTransport("{}")),
            actorId = "rung-zero-reasoning",
        ) { agentRole = "Test Agent" }

        reasoning.callLLM("no floor", requirements = CapabilityRequirement())
        reasoning.callLLM("a ZERO floor", requirements = CapabilityRequirement(minRung = CapabilityRung.ZERO))
        reasoning.callLLM("a THREE floor", requirements = CapabilityRequirement(minRung = CapabilityRung.THREE))
        reasoning.callLLM("another axis", requirements = CapabilityRequirement(minContextTokens = 128_000))
        reasoning.callLLM("no requirement")
        reasoning.callLLMForJson("no floor", requirements = CapabilityRequirement())
        reasoning.callLLMForJson("no requirement")

        assertEquals(
            listOf(
                CapabilityRung.ONE,
                CapabilityRung.ONE,
                CapabilityRung.THREE,
                CapabilityRung.ONE,
                null,
                CapabilityRung.ONE,
                null,
            ),
            relay.contexts.map { it.requirements?.minRung },
        )
        assertEquals(128_000, relay.contexts[3].requirements?.minContextTokens, "every other axis is carried through")
        assertNull(relay.contexts[4].requirements, "no requirement forwards none")
        assertNull(relay.contexts[6].requirements, "no requirement forwards none")
    }

    @Test
    fun `a floorless requirement no longer reaches a device that could serve it`() = runTest {
        val engine = availableDevice()
        val cloud = AnsweringTransport("from the cloud")
        val binding = OnDeviceInferenceBinding(engine = engine, cloud = cloud)
        val reasoning = AgentReasoning.create(
            config = configuration(relay = binding.relay, transport = binding.client),
            actorId = "rung-zero-reasoning",
        ) { agentRole = "Test Agent" }

        val text = reasoning.callLLM("Summarise the results so far.", requirements = CapabilityRequirement())

        // The device is available and its window holds the prompt: before AMPR-459 it won on price.
        assertEquals("from the cloud", text)
        assertEquals(0, engine.generateCount, "the engine is never handed a prompt")
        assertEquals(1, cloud.callCount)
    }

    @Test
    fun `a SparkBasedAgent call with a floorless requirement stays off the device`() {
        val engine = availableDevice()
        val cloud = AnsweringTransport("from the cloud")
        val binding = OnDeviceInferenceBinding(engine = engine, cloud = cloud)
        val agent = SparkBasedAgent(
            agentId = "rung-zero-agent",
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _aiConfiguration = CLOUD,
            _upstreamLlmClient = binding.client,
            _cognitiveRelay = binding.relay,
        )

        assertEquals("from the cloud", agent.callLLM("Summarise the results so far.", CapabilityRequirement()))

        assertEquals(0, engine.generateCount, "the engine is never handed a prompt")
        assertEquals(1, cloud.callCount)
    }

    // ========================================================================
    // Path 3: a catalog that re-rates the on-device model
    // ========================================================================

    @Test
    fun `the bundled catalog rates every model that executes on the device ZERO`() = runTest {
        val local = InMemoryModelDescriptorRegistry().all().filter { it.executesLocally }

        assertTrue(local.isNotEmpty())
        assertTrue(local.all { it.rung == CapabilityRung.ZERO }, "rated $local")
    }

    @Test
    fun `a seed that rates the on-device model ONE is refused`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            InMemoryModelDescriptorRegistry(seed = bundledWithDeviceAt(CapabilityRung.ONE))
        }

        assertNamesTheRule(refusal)
        assertContains(refusal.message.orEmpty(), ON_DEVICE_MODEL)
    }

    @Test
    fun `a refresh that re-rates the on-device model fails and keeps the catalog it had`() = runTest {
        val rerating = ModelDescriptorSource { Result.success(bundledWithDeviceAt(CapabilityRung.ONE)) }
        val registry = InMemoryModelDescriptorRegistry(source = rerating)
        val before = registry.all().toSet()

        val result = registry.refresh()

        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        assertEquals(before, registry.all().toSet(), "the previous catalog is intact")
        assertEquals(CapabilityRung.ZERO, registry.descriptorFor(ON_DEVICE_MODEL)?.rung)
        assertTrue(InMemoryModelDescriptorRegistry.from(rerating).isFailure)
    }

    @Test
    fun `registering a re-rated on-device model throws and changes nothing`() = runTest {
        val registry = InMemoryModelDescriptorRegistry()
        val onDevice = assertNotNull(registry.descriptorFor(ON_DEVICE_MODEL))

        assertFailsWith<IllegalArgumentException> { registry.register(onDevice.copy(rung = CapabilityRung.ONE)) }
        assertEquals(CapabilityRung.ZERO, registry.descriptorFor(ON_DEVICE_MODEL)?.rung)

        // Re-registered at ZERO is how a binding adopts the window its engine reports: still legal.
        registry.register(onDevice.copy(maxContextTokens = 32_768))
        assertEquals(32_768, registry.descriptorFor(ON_DEVICE_MODEL)?.maxContextTokens)
    }

    @Test
    fun `a free model is held to the rule and an unrated local or a cloud model at ZERO is not`() = runTest {
        // Free is the device's cost policy, so a Free model executes locally wherever it is listed.
        val free = descriptor("free-model").copy(cost = CostPolicy.Free, rung = CapabilityRung.ONE)
        assertFailsWith<IllegalArgumentException> { InMemoryModelDescriptorRegistry(seed = listOf(free)) }

        val registry = InMemoryModelDescriptorRegistry(
            seed = listOf(
                // Unrated: it already fails every declared floor.
                free.copy(rung = null),
                descriptor("cloud-at-zero").copy(rung = CapabilityRung.ZERO),
                descriptor("cloud-at-four").copy(rung = CapabilityRung.FOUR),
            ),
        )
        assertEquals(3, registry.all().size)
    }

    // ========================================================================
    // Path 5: the Decide adapter
    // ========================================================================

    @Test
    fun `the Decide adapter raises a forwarded requirement and leaves a context without one alone`() = runTest {
        val relay = RecordingRelay()
        val service = AgentLLMService(configuration(relay = relay, transport = AnsweringTransport(YES)))
        val request = DecisionRequest(state = "The run wrote the file.", questions = mapOf("done" to DONE))

        val contexts = listOf(
            CapabilityRequirement(),
            CapabilityRequirement(minRung = CapabilityRung.THREE),
            null,
        ).map { requirement -> RoutingContext(phase = CognitivePhase.PLAN, requirements = requirement) }
        contexts.forEach { context -> ModelBackedDecisionClient(service, context).decide(request, CLOUD) }
        // No context at all reaches no relay: the judgment goes to the configured model.
        ModelBackedDecisionClient(service).decide(request, CLOUD)

        assertEquals(
            listOf(CapabilityRung.ONE, CapabilityRung.THREE, null),
            relay.contexts.map { it.requirements?.minRung },
        )
        assertNull(relay.contexts[2].requirements, "a context with no requirement forwards none")
        assertTrue(relay.contexts.all { it.phase == CognitivePhase.PLAN }, "the rest of the context is kept")
    }

    @Test
    fun `a judgment with a floorless requirement never reaches a device that could serve it`() = runTest {
        val engine = availableDevice()
        val cloud = AnsweringTransport(YES)
        val binding = OnDeviceInferenceBinding(engine = engine, cloud = cloud)
        val client = ModelBackedDecisionClient(
            llmService = AgentLLMService(configuration(relay = binding.relay, transport = binding.client)),
            routingContext = RoutingContext(requirements = CapabilityRequirement()),
            localityClassifier = binding.client,
        )

        val judgment = client.decide(DecisionRequest("The run wrote the file.", mapOf("done" to DONE)), CLOUD)
            .judgments.getValue("done")

        assertEquals(0, engine.generateCount, "the engine is never handed a judgment")
        assertEquals(1, cloud.callCount)
        assertEquals(InferenceLocality.CLOUD, judgment.locality)
    }

    // ========================================================================
    // Fixtures
    // ========================================================================

    /** Every refusal says which rule it enforces and where that rule is written down. */
    private fun assertNamesTheRule(refusal: IllegalArgumentException) {
        val message = refusal.message.orEmpty()
        assertContains(message, "display text only")
        assertContains(message, "docs/concepts/cognitive-relay.md")
    }

    private fun agent(
        minimumRung: CapabilityRung? = null,
        configuration: AIConfiguration? = CLOUD,
    ): SparkBasedAgent<CodeState> = SparkBasedAgent(
        agentId = "rung-zero-agent",
        cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
        initialState = CodeState.blank,
        _aiConfiguration = configuration,
        _minimumRung = minimumRung,
    )

    /** An agent with no floor of its own, so every floor a relay sees is the call's. */
    private fun configuration(relay: CognitiveRelay, transport: UpstreamLlmClient) = AgentConfiguration(
        agentDefinition = AgentDefinition.Custom(
            name = "rung-zero-test",
            description = "An agent with no floor",
            prompt = "You are a test agent.",
        ),
        aiConfiguration = CLOUD,
        cognitiveRelay = relay,
        upstreamLlmClient = transport,
    )

    /**
     * A device that can serve, with a window the binding adopts that is far larger than the
     * catalog's 4,096: nothing about a prompt's size is what keeps it off the device here.
     */
    private fun availableDevice() = FakeLocalInferenceEngine(
        capacity = LocalCapacity(
            available = true,
            modelId = ON_DEVICE_MODEL,
            maxContextTokens = 32_768,
            providerId = AIProvider_OnDevice.id,
        ),
        respond = { Result.success("from the device") },
    )

    /** The bundled catalog with every model that executes on the device re-rated to [rung]. */
    private fun bundledWithDeviceAt(rung: CapabilityRung): List<ModelDescriptor> =
        InMemoryModelDescriptorRegistry.defaultModelDescriptors()
            .map { descriptor -> if (descriptor.executesLocally) descriptor.copy(rung = rung) else descriptor }

    private fun descriptor(name: String) = ModelDescriptor(
        modelName = name,
        providerId = AIProvider_Anthropic.id,
        capabilities = emptySet(),
        reasoning = RelativeReasoning.NORMAL,
        maxContextTokens = 200_000,
        supportedInputs = SupportedInputs.TEXT,
    )

    /** Records every context it is asked to resolve and hands back the fallback. */
    private class RecordingRelay : CognitiveRelay {
        val contexts = mutableListOf<RoutingContext>()

        override val config: RelayConfig = RelayConfig()

        override suspend fun resolve(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): AIConfiguration {
            contexts += context
            return fallbackConfiguration
        }

        override suspend fun updateConfig(newConfig: RelayConfig) = Unit
    }

    /** Answers every call with [answer] and records which model each was sent for. */
    private class AnsweringTransport(private val answer: String) : UpstreamLlmClient {
        val models = mutableListOf<String>()

        val callCount: Int
            get() = models.size

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            models += configuration.model.name
            return ChatCompletion(
                id = "answering-transport",
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(index = 0, message = ChatMessage(role = ChatRole.Assistant, content = answer)),
                ),
            )
        }
    }

    private companion object {
        val CLOUD: AIConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
        val ON_DEVICE: AIConfiguration =
            AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)
        val ON_DEVICE_MODEL: String = AIModel_OnDevice.AppleFoundationModels.name
        val WORKSPACE = ExecutionWorkspace(baseDirectory = "/tmp/rung-zero-eligibility")

        val DONE: Question = Question.Noul.of(
            instructions = "Did the run do what was asked?",
            whenTrue = "It did",
            whenFalse = "It did not",
        )
        const val YES = """{"answer": "true", "confidence": "high"}"""
    }
}
