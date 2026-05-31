package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.domain.reasoning.Confidence

class EmissionSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val baseProvenance = EmissionProvenance(
        runId = "run-1",
        workflowId = "wf-1",
        sourceEventId = "evt-1",
        toolInvocationId = "tool-inv-1",
        pluginId = "plugin-x",
        modelId = "claude-sonnet-4-0",
        inputDigest = "abcdef0123456789",
    )

    private fun emission(payload: EmissionPayload, kind: EmissionKind): Emission = Emission(
        id = "emission-id",
        kind = kind,
        payload = payload,
        affordances = listOf(
            Affordance(
                id = "aff-1",
                label = "Confirm",
                signalPayload = JsonPrimitive("ok"),
            ),
        ),
        confidence = Confidence.HIGH,
        provenance = baseProvenance,
        dedupKey = null,
        producedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    @Test
    fun `Prose payload round-trips`() {
        val original = emission(
            payload = EmissionPayload.Prose(text = "hello", format = ProseFormat.MARKDOWN),
            kind = EmissionKind.Prose,
        )

        val encoded = json.encodeToString(Emission.serializer(), original)
        val decoded = json.decodeFromString(Emission.serializer(), encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"EmissionPayload.Prose\""))
        assertTrue(encoded.contains("\"type\":\"EmissionKind.Prose\""))
    }

    @Test
    fun `Decision payload round-trips`() {
        val original = emission(
            payload = EmissionPayload.Decision(prompt = "Which?", context = "background"),
            kind = EmissionKind.Decision,
        )

        val encoded = json.encodeToString(Emission.serializer(), original)
        val decoded = json.decodeFromString(Emission.serializer(), encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"EmissionPayload.Decision\""))
        assertTrue(encoded.contains("\"type\":\"EmissionKind.Decision\""))
    }

    @Test
    fun `Confirmation payload round-trips`() {
        val original = emission(
            payload = EmissionPayload.Confirmation(
                action = "delete branch",
                preview = "deleting refs/heads/feature/x",
                dangerLevel = DangerLevel.HIGH,
            ),
            kind = EmissionKind.Confirmation,
        )

        val encoded = json.encodeToString(Emission.serializer(), original)
        val decoded = json.decodeFromString(Emission.serializer(), encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"EmissionPayload.Confirmation\""))
        assertTrue(encoded.contains("\"type\":\"EmissionKind.Confirmation\""))
    }

    @Test
    fun `Sensor payload round-trips`() {
        val original = emission(
            payload = EmissionPayload.Sensor(
                label = "queue depth",
                value = "42",
                unit = "items",
                refreshUri = "/q/depth",
            ),
            kind = EmissionKind.Sensor,
        )

        val encoded = json.encodeToString(Emission.serializer(), original)
        val decoded = json.decodeFromString(Emission.serializer(), encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"EmissionPayload.Sensor\""))
        assertTrue(encoded.contains("\"type\":\"EmissionKind.Sensor\""))
    }

    @Test
    fun `provenance fields survive round-trip`() {
        val original = emission(
            payload = EmissionPayload.Prose(text = "x", format = ProseFormat.PLAIN),
            kind = EmissionKind.Prose,
        )

        val encoded = json.encodeToString(Emission.serializer(), original)
        val decoded = json.decodeFromString(Emission.serializer(), encoded)

        assertEquals(baseProvenance, decoded.provenance)
        assertEquals("run-1", decoded.provenance.runId)
        assertEquals("plugin-x", decoded.provenance.pluginId)
        assertEquals("abcdef0123456789", decoded.provenance.inputDigest)
    }
}
