package link.socket.ampere.llm.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

class DeterministicDecisionClientTest {

    private val configuration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
    private val satisfied = Question.Noul.of(
        instructions = "Does the outcome satisfy the goal?",
        whenTrue = "The outcome reports success",
        whenFalse = "The outcome reports anything else",
    )

    @Test
    fun `a deterministic judge yields a measured one-hot judgment that ran on-device`() = runTest {
        val client = DeterministicDecisionClient(name = "any-success") { state, _ ->
            if (state.contains("SUCCESS")) Question.Noul.TRUE else Question.Noul.FALSE
        }

        val response = client.decide(
            DecisionRequest(state = "outcome: SUCCESS", questions = mapOf("done" to satisfied)),
            configuration,
        )

        val judgment = response.judgments.getValue("done")
        assertEquals("true", judgment.answer)
        assertEquals(mapOf("true" to 1.0, "false" to 0.0), judgment.distribution)
        assertEquals(1.0, judgment.confidence)
        assertEquals(ConfidenceSource.MEASURED, judgment.source)
        assertEquals(InferenceLocality.ON_DEVICE, judgment.locality)
        assertEquals(ModelSnapshot(DeterministicDecisionClient.PROVIDER_ID, "any-success"), judgment.modelSnapshot)
    }

    @Test
    fun `a judge that answers outside the declared keys is a malformed response`() = runTest {
        val client = DeterministicDecisionClient(name = "wrong") { _, _ -> "maybe" }

        assertFailsWith<MalformedDecisionResponseException> {
            client.decide(DecisionRequest(state = "s", questions = mapOf("q" to satisfied)), configuration)
        }
    }
}
