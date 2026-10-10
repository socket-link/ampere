package link.socket.ampere.llm.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * J1: the decision transport is opted into, never inherited. An agent whose
 * configuration carries none fails on `decide`; one that carries one reaches it
 * through [AgentConfiguration.upstreamDecisionClient] with no further plumbing.
 */
class UpstreamDecisionClientPlumbingTest {

    private val aiConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
    private val baseConfig = AgentConfiguration(agentDefinition = WriteCodeAgent, aiConfiguration = aiConfiguration)
    private val question = Question.Noul.of("Is it done?", whenTrue = "Done", whenFalse = "Not done")

    private fun reasoningWith(config: AgentConfiguration): AgentReasoning =
        AgentReasoning.create(config = config, executorId = "plumbing-test") { agentRole = "Test Agent" }

    @Test
    fun `default config carries no decision client and decide throws`() = runTest {
        assertNull(baseConfig.upstreamDecisionClient)

        val failure = assertFailsWith<MissingUpstreamDecisionClientException> {
            reasoningWith(baseConfig).decide(state = "s", questions = mapOf("done" to question))
        }
        assertNotNull(failure.message)
    }

    @Test
    fun `a client on the configuration is the one decide calls and its judgments come back`() = runTest {
        val recorder = RecordingDecisionClient()
        val reasoning = reasoningWith(baseConfig.copy(upstreamDecisionClient = recorder))

        val response = reasoning.decide(state = "state text", questions = mapOf("done" to question))

        val request = assertNotNull(recorder.lastRequest)
        assertEquals("state text", request.state)
        assertSame(question, request.questions.getValue("done"))
        assertSame(aiConfiguration, recorder.lastConfiguration)
        assertEquals("true", response.judgments.getValue("done").answer)
    }

    @Test
    fun `the chat seam does not stand in for the decision seam`() = runTest {
        // A configuration with an LLM transport but no decision transport still has no decision transport.
        val config = baseConfig.copy(upstreamLlmClient = link.socket.ampere.llm.BundledUpstreamLlmClient)

        assertFailsWith<MissingUpstreamDecisionClientException> {
            reasoningWith(config).decide(state = "s", questions = mapOf("done" to question))
        }
    }
}

internal class RecordingDecisionClient : UpstreamDecisionClient {
    var lastRequest: DecisionRequest? = null
        private set
    var lastConfiguration: AIConfiguration? = null
        private set

    private val delegate = DeterministicDecisionClient(name = "recording") { _, q -> q.answerKeys.first() }

    override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
        lastRequest = request
        lastConfiguration = configuration
        return delegate.decide(request, configuration)
    }
}
