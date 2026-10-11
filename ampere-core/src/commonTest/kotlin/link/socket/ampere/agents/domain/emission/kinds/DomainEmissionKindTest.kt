package link.socket.ampere.agents.domain.emission.kinds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.ProseFormat

class DomainEmissionKindTest {

    @Serializable
    private data class Gauge(val label: String, val reading: Int, val caption: String = "n/a")

    private val gaugeKind = DomainEmissionKind(
        id = "gauge",
        schemaVersion = 2,
        serializer = Gauge.serializer(),
        fallbackText = { "${it.label}: ${it.reading}" },
    )

    @Test
    fun `encode carries the id and version and writes defaults`() {
        val payload = gaugeKind.encode(Gauge(label = "load", reading = 7))

        assertEquals(EmissionKind.Domain("gauge"), gaugeKind.kind)
        assertEquals("gauge", payload.kindId)
        assertEquals(2, payload.schemaVersion)
        assertEquals("load: 7", payload.fallbackText)
        assertEquals(JsonPrimitive("n/a"), payload.body.jsonObject["caption"])
    }

    @Test
    fun `decodeOrNull reads back what encode wrote`() {
        val gauge = Gauge(label = "load", reading = 7, caption = "ok")
        assertEquals(gauge, gaugeKind.decodeOrNull(gaugeKind.encode(gauge)))
    }

    @Test
    fun `decodeOrNull is null for a core payload`() {
        assertNull(gaugeKind.decodeOrNull(EmissionPayload.Prose("hello", ProseFormat.PLAIN)))
    }

    @Test
    fun `decodeOrNull is null for another kind id`() {
        val other = gaugeKind.encode(Gauge("load", 7)).copy(kindId = "dial")
        assertNull(gaugeKind.decodeOrNull(other))
    }

    @Test
    fun `decodeOrNull is null for a newer schema version`() {
        val newer = gaugeKind.encode(Gauge("load", 7)).copy(schemaVersion = 3)
        assertNull(gaugeKind.decodeOrNull(newer))
    }

    @Test
    fun `decodeOrNull reads an older body that lacks a defaulted field`() {
        val older = EmissionPayload.Domain(
            kindId = "gauge",
            schemaVersion = 1,
            body = buildJsonObject {
                put("label", JsonPrimitive("load"))
                put("reading", JsonPrimitive(7))
            },
            fallbackText = "load: 7",
        )
        assertEquals(Gauge(label = "load", reading = 7, caption = "n/a"), gaugeKind.decodeOrNull(older))
    }

    @Test
    fun `decodeOrNull ignores keys this descriptor does not know`() {
        val withExtra = gaugeKind.encode(Gauge("load", 7)).let { payload ->
            payload.copy(body = JsonObject(payload.body.jsonObject + ("colour" to JsonPrimitive("red"))))
        }
        assertEquals(Gauge("load", 7), gaugeKind.decodeOrNull(withExtra))
    }

    @Test
    fun `decodeOrNull is null for a body that does not fit the serializer`() {
        val broken = gaugeKind.encode(Gauge("load", 7))
            .copy(body = buildJsonObject { put("label", JsonPrimitive("load")) })
        assertNull(gaugeKind.decodeOrNull(broken))
    }

    @Test
    fun `the kind id must be a lowercase slug`() {
        assertFailsWith<IllegalArgumentException> { EmissionKind.Domain("Recipe") }
        assertFailsWith<IllegalArgumentException> { EmissionKind.Domain("meal plan") }
        assertFailsWith<IllegalArgumentException> { EmissionKind.Domain("") }
        assertFailsWith<IllegalArgumentException> { EmissionKind.Domain("-recipe") }
        assertFailsWith<IllegalArgumentException> { DomainEmissionKind("Recipe", 1, Gauge.serializer()) { "x" } }
        EmissionKind.Domain("meal-plan")
        EmissionKind.Domain("recipe2")
    }

    @Test
    fun `a domain payload requires a version and non-blank fallback text`() {
        assertFailsWith<IllegalArgumentException> {
            EmissionPayload.Domain("gauge", schemaVersion = 0, body = JsonPrimitive(1), fallbackText = "x")
        }
        assertFailsWith<IllegalArgumentException> {
            EmissionPayload.Domain("gauge", schemaVersion = 1, body = JsonPrimitive(1), fallbackText = "  ")
        }
        assertFailsWith<IllegalArgumentException> {
            EmissionPayload.Domain("Gauge", schemaVersion = 1, body = JsonPrimitive(1), fallbackText = "x")
        }
    }
}
