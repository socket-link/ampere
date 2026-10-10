package link.socket.ampere.llm.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality

class JudgmentTest {

    private val snapshot = ModelSnapshot(providerId = "test", modelId = "judge")
    private val score = Question.Score(instructions = "How bad?", criteria = listOf("fine", "degraded", "down"))

    @Test
    fun `confidence defaults to the distribution's mass on the answer`() {
        val judgment = Judgment(
            answer = "1",
            distribution = mapOf("0" to 0.0, "1" to 0.85, "2" to 0.15),
            source = ConfidenceSource.MEASURED,
            modelSnapshot = snapshot,
            locality = InferenceLocality.CLOUD,
        )

        assertEquals(0.85, judgment.confidence)
        assertEquals(1.15, judgment.expectedScore()!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun `a judgment with no distribution has no expected score`() {
        val judgment = Judgment(
            answer = "degraded",
            distribution = null,
            source = ConfidenceSource.SELF_REPORTED,
            modelSnapshot = snapshot,
            locality = InferenceLocality.CLOUD,
        )

        assertNull(judgment.confidence)
        assertNull(judgment.expectedScore())
    }

    @Test
    fun `a distribution must cover the answer`() {
        assertFailsWith<IllegalArgumentException> {
            Judgment(
                answer = "2",
                distribution = mapOf("0" to 1.0),
                source = ConfidenceSource.MEASURED,
                modelSnapshot = snapshot,
                locality = InferenceLocality.CLOUD,
            )
        }
    }

    @Test
    fun `oneHot puts all the mass on the answer and is measured`() {
        val judgment =
            Judgment.oneHot(score, answer = "2", modelSnapshot = snapshot, locality = InferenceLocality.ON_DEVICE)

        assertEquals(mapOf("0" to 0.0, "1" to 0.0, "2" to 1.0), judgment.distribution)
        assertEquals(1.0, judgment.confidence)
        assertEquals(ConfidenceSource.MEASURED, judgment.source)
        assertEquals(2.0, judgment.expectedScore())
    }

    @Test
    fun `oneHot refuses an answer the question did not declare`() {
        assertFailsWith<IllegalArgumentException> {
            Judgment.oneHot(score, answer = "3", modelSnapshot = snapshot, locality = InferenceLocality.ON_DEVICE)
        }
    }
}
