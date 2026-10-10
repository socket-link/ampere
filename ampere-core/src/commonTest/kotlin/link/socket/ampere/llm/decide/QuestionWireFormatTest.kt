package link.socket.ampere.llm.decide

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json

/**
 * J2/J3: the serialized form of a [Question] *is* the System One wire body, and
 * every question type survives a round trip.
 */
class QuestionWireFormatTest {

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private val noul = Question.Noul.of(
        instructions = "Is the customer asking for money back?",
        whenTrue = "The customer wants a charge reversed or refunded",
        whenFalse = "The customer wants something other than money back",
    )
    private val choice = Question.Choice(
        instructions = "Which team should handle this ticket?",
        criteria = linkedMapOf(
            "billing" to "Charges, invoices, and refunds",
            "technical" to "Bugs, outages, and broken features",
            "account" to "Login, password, and profile changes",
        ),
    )
    private val score = Question.Score(
        instructions = "How severe is this bug report?",
        criteria = listOf(
            "Cosmetic; no impact to functionality",
            "Broken or degraded feature, but a workaround exists",
            "Blocking issue; no workaround exists",
        ),
    )

    @Test
    fun `a noul round-trips and serializes as the wire shape`() {
        val text = json.encodeToString(Question.serializer(), noul)

        assertContains(text, "\"type\":\"noul\"")
        assertContains(text, "\"criteria\":{\"true\":")
        val decoded = json.decodeFromString(Question.serializer(), text)
        assertIs<Question.Noul>(decoded)
        assertEquals(noul, decoded)
        assertEquals(listOf("true", "false"), decoded.answerKeys)
    }

    @Test
    fun `a choice round-trips and its options are its criteria keys in order`() {
        val text = json.encodeToString(Question.serializer(), choice)

        assertContains(text, "\"type\":\"choice\"")
        val decoded = json.decodeFromString(Question.serializer(), text)
        assertIs<Question.Choice>(decoded)
        assertEquals(choice, decoded)
        assertEquals(listOf("billing", "technical", "account"), decoded.options)
        assertEquals(decoded.options, decoded.answerKeys)
        assertEquals("Bugs, outages, and broken features", decoded.describe("technical"))
    }

    @Test
    fun `a score round-trips with an ordered list of levels`() {
        val text = json.encodeToString(Question.serializer(), score)

        assertContains(text, "\"type\":\"score\"")
        assertContains(text, "\"criteria\":[\"Cosmetic")
        val decoded = json.decodeFromString(Question.serializer(), text)
        assertIs<Question.Score>(decoded)
        assertEquals(score, decoded)
        assertEquals(listOf("0", "1", "2"), decoded.levels)
        assertEquals("Blocking issue; no workaround exists", decoded.describe("2"))
    }

    @Test
    fun `a request round-trips with questions keyed by id`() {
        val request = DecisionRequest(
            state = "I was charged twice for September.",
            questions = mapOf("refund" to noul, "team" to choice, "severity" to score),
        )

        val text = json.encodeToString(DecisionRequest.serializer(), request)
        val decoded = json.decodeFromString(DecisionRequest.serializer(), text)

        assertEquals(request, decoded)
        assertContains(text, "\"refund\":{\"type\":\"noul\"")
    }

    @Test
    fun `a noul requires exactly the true and false criteria`() {
        assertFailsWith<IllegalArgumentException> {
            Question.Noul(instructions = "x", criteria = mapOf("yes" to "a", "no" to "b"))
        }
        assertFailsWith<IllegalArgumentException> {
            Question.Noul(instructions = "x", criteria = mapOf("true" to "a"))
        }
    }

    @Test
    fun `a choice needs at least two options and a score at least two levels`() {
        assertFailsWith<IllegalArgumentException> {
            Question.Choice(instructions = "x", criteria = mapOf("only" to "one"))
        }
        assertFailsWith<IllegalArgumentException> {
            Question.Score(instructions = "x", criteria = listOf("one"))
        }
    }

    @Test
    fun `a request needs at least one question`() {
        assertFailsWith<IllegalArgumentException> { DecisionRequest(state = "s", questions = emptyMap()) }
    }

    @Test
    fun `a reworded question is a new version`() {
        val reworded = noul.copy(instructions = "Does the customer want a refund?")

        assertEquals(noul.version, noul.copy().version)
        assertNotEquals(noul.version, reworded.version)
        assertEquals(64, noul.version.length)
    }

    @Test
    fun `the state digest is a full SHA-256 and differs per state`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            stateDigest("hello"),
        )
        assertNotEquals(stateDigest("hello"), stateDigest("hello "))
    }
}
