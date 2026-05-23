package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * Plumbing test for [AgentConfiguration.upstreamLlmClient].
 *
 * This is the seam that lets a runtime-default `UpstreamLlmClient` (from
 * `Ampere.fromEnvironment(upstreamLlmClient = ...)`) reach
 * [AgentLLMService.call] via [AgentConfiguration] without explicit
 * plumbing at every construction site. See
 * [link.socket.ampere.agents.domain.reasoning.AgentReasoning] which reads
 * `config.upstreamLlmClient` when constructing the service.
 */
class UpstreamLlmClientRuntimePlumbingTest {

    private val baseConfig = AgentConfiguration(
        agentDefinition = WriteCodeAgent,
        aiConfiguration = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_4,
        ),
    )

    @Test
    fun `AgentConfiguration upstreamLlmClient field flows through AgentLLMService call`() = runTest {
        val recorder = RecorderClient(cannedResponse = "from-config-client")
        val configWithClient = baseConfig.copy(upstreamLlmClient = recorder)

        // No explicit upstreamLlmClient passed to the constructor —
        // it must default-read from the config. This is the path
        // SparkBasedAgent → AgentReasoning → AgentLLMService travels.
        val service = AgentLLMService(agentConfiguration = configWithClient)

        val response = service.call(prompt = "plumbing", systemMessage = "test")

        assertEquals("from-config-client", response)
        val captured = assertNotNull(recorder.lastRequest, "Recorder must capture the request")
        assertEquals(2, captured.messages.size)
    }

    @Test
    fun `explicit constructor arg overrides AgentConfiguration field`() = runTest {
        val configClient = RecorderClient(cannedResponse = "from-config")
        val explicitClient = RecorderClient(cannedResponse = "from-explicit-arg")

        val service = AgentLLMService(
            agentConfiguration = baseConfig.copy(upstreamLlmClient = configClient),
            upstreamLlmClient = explicitClient,
        )

        val response = service.call(prompt = "override")

        assertEquals("from-explicit-arg", response)
        assertNotNull(explicitClient.lastRequest, "Explicit client must be the one invoked")
        assertEquals(null, configClient.lastRequest, "Config-supplied client must be ignored")
    }

    @Test
    fun `default config carries BundledUpstreamLlmClient`() {
        // The Wave 0 contract: callers who don't touch the new field get
        // the pre-seam direct-call behavior preserved.
        assertSame(BundledUpstreamLlmClient, baseConfig.upstreamLlmClient)
    }
}

private class RecorderClient(
    private val cannedResponse: String,
) : UpstreamLlmClient {

    var lastRequest: ChatCompletionRequest? = null
        private set

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        lastRequest = request
        return ChatCompletion(
            id = "rec",
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(
                        role = ChatRole.Assistant,
                        content = cannedResponse,
                    ),
                ),
            ),
        )
    }
}
