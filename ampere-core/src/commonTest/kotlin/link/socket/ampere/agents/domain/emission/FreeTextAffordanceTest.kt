package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FreeTextAffordanceTest {

    @Test
    fun `freeTextAffordance produces affordance with type free-text`() {
        val builder = AffordanceBuilder()
        val affordance = builder.freeTextAffordance("Describe the issue")

        val payload = affordance.signalPayload as JsonObject
        assertEquals("free-text", (payload["type"] as? JsonPrimitive)?.content)
        assertEquals("Describe the issue", (payload["prompt"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `freeTextAffordance label matches the prompt`() {
        val builder = AffordanceBuilder()
        val affordance = builder.freeTextAffordance("Provide response")
        assertEquals("Provide response", affordance.label)
    }

    @Test
    fun `extractFreeText returns text from matching payload`() {
        val payload = JsonObject(
            mapOf(
                "type" to JsonPrimitive("free-text"),
                "text" to JsonPrimitive("hello world"),
            ),
        )
        val text = extractFreeText(payload)
        assertEquals("hello world", text)
    }

    @Test
    fun `extractFreeText returns null for non-free-text payload`() {
        val payload = JsonObject(mapOf("type" to JsonPrimitive("binary"), "text" to JsonPrimitive("yes")))
        assertNull(extractFreeText(payload))
    }

    @Test
    fun `extractFreeText returns null for null input`() {
        assertNull(extractFreeText(null))
    }

    @Test
    fun `extractFreeText returns null for primitive input`() {
        assertNull(extractFreeText(JsonPrimitive("yes")))
    }

    @Test
    fun `AffordanceBuilder build returns all added affordances`() {
        val builder = AffordanceBuilder()
        builder.affordance("Approve")
        builder.affordance("Reject")
        builder.freeTextAffordance("Explain why")

        val affordances = builder.build()
        assertEquals(3, affordances.size)
        assertEquals("Approve", affordances[0].label)
        assertEquals("Reject", affordances[1].label)
        assertEquals("Explain why", affordances[2].label)
    }

    @Test
    fun `affordance uses label as default signal payload`() {
        val builder = AffordanceBuilder()
        val affordance = builder.affordance("Yes")
        val payload = affordance.signalPayload as? JsonPrimitive
        assertNotNull(payload)
        assertEquals("Yes", payload.content)
    }

    @Test
    fun `multiple freeTextAffordances get unique ids`() {
        val builder = AffordanceBuilder()
        val a1 = builder.freeTextAffordance("First prompt")
        val a2 = builder.freeTextAffordance("Second prompt")
        assertTrue(a1.id != a2.id)
    }
}
