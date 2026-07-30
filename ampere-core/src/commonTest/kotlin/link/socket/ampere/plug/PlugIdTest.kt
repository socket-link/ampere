package link.socket.ampere.plug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

class PlugIdTest {

    @Test
    fun `accepts lowercase alphanumeric with dashes and underscores`() {
        listOf("github-plug", "calendar_plug", "plug1", "x", "a-b_c-1").forEach { value ->
            assertEquals(value, PlugId(value).value)
        }
    }

    @Test
    fun `rejects a dotted id like the Calendar Plug incident`() {
        assertFailsWith<IllegalArgumentException> {
            PlugId("link.socket.plug.calendar")
        }
    }

    @Test
    fun `rejects uppercase`() {
        assertFailsWith<IllegalArgumentException> {
            PlugId("GitHub-Plug")
        }
    }

    @Test
    fun `rejects blank`() {
        assertFailsWith<IllegalArgumentException> {
            PlugId("")
        }
    }

    @Test
    fun `rejects whitespace`() {
        assertFailsWith<IllegalArgumentException> {
            PlugId("github plug")
        }
    }

    @Test
    fun `fails at construction during json decoding of an invalid id`() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(PlugId.serializer(), "\"link.socket.plug.calendar\"")
        }
    }

    @Test
    fun `round-trips through json as the bare string value`() {
        val id = PlugId("github-plug")

        val encoded = Json.encodeToString(PlugId.serializer(), id)
        assertEquals("\"github-plug\"", encoded)

        val decoded = Json.decodeFromString(PlugId.serializer(), encoded)
        assertEquals(id, decoded)
    }
}
