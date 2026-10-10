package link.socket.ampere.llm.decide

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

class ModelBackedDecisionClientTest {

    private val aiConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    private val team = Question.Choice(
        instructions = "Which team should handle this ticket?",
        criteria = linkedMapOf(
            "billing" to "Charges, invoices, and refunds",
            "technical" to "Bugs, outages, and broken features",
        ),
    )
    private val refund = Question.Noul.of(
        instructions = "Is the customer asking for money back?",
        whenTrue = "Wants a refund",
        whenFalse = "Wants something else",
    )

    private fun clientAnswering(vararg replies: String): Pair<ModelBackedDecisionClient, RecorderClient> {
        val recorder = RecorderClient(replies.toMutableList())
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = aiConfiguration,
                upstreamLlmClient = recorder,
            ),
        )
        return ModelBackedDecisionClient(service) to recorder
    }

    @Test
    fun `a generative answer is self-reported with no distribution and an F10-mapped confidence`() = runTest {
        val (client, recorder) = clientAnswering(
            """```json
{"answer": "billing", "confidence": "high"}
```""",
        )

        val response = client.decide(
            DecisionRequest(state = "Charged twice for the Pro plan.", questions = mapOf("team" to team)),
            aiConfiguration,
        )

        val judgment = response.judgments.getValue("team")
        assertEquals("billing", judgment.answer)
        assertNull(judgment.distribution)
        assertEquals(ConfidenceSource.SELF_REPORTED, judgment.source)
        assertEquals(0.75, judgment.confidence)
        assertEquals(InferenceLocality.CLOUD, judgment.locality)
        assertEquals(AIProvider_Anthropic.id, judgment.modelSnapshot.providerId)
        assertEquals(AIModel_Claude.Sonnet_5.name, judgment.modelSnapshot.modelId)

        val prompt = assertNotNull(recorder.lastRequest).messages.last().content.orEmpty()
        assertContains(prompt, "Charged twice for the Pro plan.")
        assertContains(prompt, "\"billing\": Charges, invoices, and refunds")
        assertContains(prompt, "Which team should handle this ticket?")
    }

    @Test
    fun `a noul accepts yes and boolean spellings of its keys`() = runTest {
        val (client, _) = clientAnswering("""{"answer": "yes", "confidence": "low"}""", """{"answer": false}""")

        val first = client.decide(DecisionRequest("s", mapOf("refund" to refund)), aiConfiguration)
        val second = client.decide(DecisionRequest("s", mapOf("refund" to refund)), aiConfiguration)

        assertEquals("true", first.judgments.getValue("refund").answer)
        assertEquals(0.25, first.judgments.getValue("refund").confidence)
        assertEquals("false", second.judgments.getValue("refund").answer)
        assertNull(second.judgments.getValue("refund").confidence)
    }

    @Test
    fun `an answer outside the declared keys is a malformed response`() = runTest {
        val (client, _) = clientAnswering("""{"answer": "sales", "confidence": "high"}""")

        assertFailsWith<MalformedDecisionResponseException> {
            client.decide(DecisionRequest("s", mapOf("team" to team)), aiConfiguration)
        }
    }
}

private class RecorderClient(
    private val replies: MutableList<String>,
) : link.socket.ampere.llm.UpstreamLlmClient {

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
                    message = ChatMessage(role = ChatRole.Assistant, content = replies.removeAt(0)),
                ),
            ),
        )
    }
}
