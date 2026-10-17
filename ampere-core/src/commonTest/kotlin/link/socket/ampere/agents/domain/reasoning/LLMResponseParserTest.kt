package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class LLMResponseParserTest {

    private fun clean(response: String) = LLMResponseParser.cleanJsonResponse(response)

    // Replies that parsed before the extraction rule: each must come back exactly as it did.

    @Test
    fun `a bare object is returned as it was`() {
        assertEquals("""{"answer": "true"}""", clean("""{"answer": "true"}"""))
    }

    @Test
    fun `a bare array is returned as it was`() {
        assertEquals("""[1, 2, 3]""", clean("""[1, 2, 3]"""))
    }

    @Test
    fun `a json-fenced object is unwrapped`() {
        assertEquals("{\"a\": 1}", clean("```json\n{\"a\": 1}\n```"))
    }

    @Test
    fun `an uppercase JSON-fenced object is unwrapped`() {
        assertEquals("{\"a\": 1}", clean("```JSON\n{\"a\": 1}\n```"))
    }

    @Test
    fun `a bare-fenced object is unwrapped`() {
        assertEquals("{\"a\": 1}", clean("```\n{\"a\": 1}\n```"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("{\"a\": 1}", clean("  \n\t{\"a\": 1}\n\n  "))
        assertEquals("{\"a\": 1}", clean("\n  ```json\n{\"a\": 1}\n```  \n"))
    }

    @Test
    fun `a parsing reply keeps its inner whitespace byte for byte`() {
        val pretty = "{\n  \"answer\": \"false\",\n  \"confidence\": \"high\"\n}"
        assertEquals(pretty, clean("```json\n$pretty\n```"))
    }

    @Test
    fun `a reply with no JSON at all is returned trimmed and still fails to parse`() {
        assertEquals("I cannot answer that.", clean("  I cannot answer that.\n"))
        assertFailsWith<SerializationException> {
            LLMResponseParser.parseJsonElement(clean("I cannot answer that."))
        }
    }

    // Replies that failed before: the first complete value is read out of the prose.

    @Test
    fun `a fenced multi-line object followed by prose yields the object`() {
        val reply = """
            |```json
            |{
            |  "answer": "false",
            |  "confidence": "high"
            |}
            |```
            |
            |The candidate differs from the approved writes in a significant way: it touches a file none of them did.
        """.trimMargin()

        val cleaned = clean(reply)

        assertEquals("{\n  \"answer\": \"false\",\n  \"confidence\": \"high\"\n}", cleaned)
        val parsed = LLMResponseParser.parseJsonObject(cleaned)
        assertEquals("false", parsed.getValue("answer").jsonPrimitive.content)
        assertEquals("high", parsed.getValue("confidence").jsonPrimitive.content)
    }

    @Test
    fun `a one-line fenced object followed by a numbered list yields the object`() {
        val reply = "```json\n{\"answer\": \"billing\", \"confidence\": \"medium\"}\n```\n\n" +
            "Reasons:\n1. The customer mentions a charge.\n2. They ask for [a refund].\n"

        assertEquals("{\"answer\": \"billing\", \"confidence\": \"medium\"}", clean(reply))
    }

    @Test
    fun `prose then a bare object yields the object`() {
        val reply = "Here is my answer after weighing the evidence:\n\n{\"answer\": \"yes\"}"

        assertEquals("{\"answer\": \"yes\"}", clean(reply))
    }

    @Test
    fun `a bare object then prose yields the object`() {
        val reply = "{\"answer\": \"no\"}\n\nI chose no because nothing in the state mentions money."

        assertEquals("{\"answer\": \"no\"}", clean(reply))
    }

    @Test
    fun `a fenced array followed by prose yields the array`() {
        val reply = "```json\n[{\"step\": 1}, {\"step\": 2}]\n```\nThese two steps cover the task."

        val cleaned = clean(reply)

        assertEquals("[{\"step\": 1}, {\"step\": 2}]", cleaned)
        assertEquals(2, LLMResponseParser.parseJsonArray(cleaned).size)
    }

    @Test
    fun `a nested object is read to its outer close`() {
        val value = "{\"outer\": {\"inner\": {\"deep\": [1, {\"x\": 2}]}}, \"after\": true}"

        assertEquals(value, clean("Answer:\n$value\nDone."))
    }

    @Test
    fun `braces brackets and an escaped quote inside a string do not end the value`() {
        val value = """{"note": "a } and a ] and a { and a [ and a \" quote", "n": 1}"""

        val cleaned = clean("```json\n$value\n```\nThe note is quoted from the ticket.")

        assertEquals(value, cleaned)
        val parsed = LLMResponseParser.parseJsonObject(cleaned)
        assertEquals("a } and a ] and a { and a [ and a \" quote", parsed.getValue("note").jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), parsed.getValue("n"))
    }

    @Test
    fun `an escaped backslash before a closing quote still closes the string`() {
        val value = """{"path": "C:\\", "n": 2}"""

        assertEquals(value, clean("$value trailing words"))
    }

    @Test
    fun `of two objects the first wins`() {
        assertEquals("{\"answer\": \"first\"}", clean("{\"answer\": \"first\"}\n{\"answer\": \"second\"}"))
    }

    @Test
    fun `the first fenced block is read before anything outside it`() {
        val reply = "Unlike {\"answer\": \"prose\"} the answer is:\n```json\n{\"answer\": \"fenced\"}\n```"

        assertEquals("{\"answer\": \"fenced\"}", clean(reply))
    }

    @Test
    fun `an unbalanced object still fails with the same exception type`() {
        val reply = "```json\n{\"answer\": \"true\", \"confidence\": {\"level\": \"high\"}\n```\nThat is my answer."

        val cleaned = clean(reply)

        assertEquals(reply.removePrefix("```json").trim(), cleaned)
        assertFailsWith<SerializationException> { LLMResponseParser.parseJsonElement(cleaned) }
    }
}
