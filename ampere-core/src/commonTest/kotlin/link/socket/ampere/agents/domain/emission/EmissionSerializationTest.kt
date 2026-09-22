package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.domain.Principal
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
        plugId = "plug-x",
        modelId = "claude-sonnet-5",
        inputDigest = "abcdef0123456789",
        parentEmissionId = "parent-emission",
        principal = Principal.Ambient,
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
        assertEquals("plug-x", decoded.provenance.plugId)
        assertEquals("abcdef0123456789", decoded.provenance.inputDigest)
        assertEquals("parent-emission", decoded.provenance.parentEmissionId)
        assertEquals(Principal.Ambient, decoded.provenance.principal)
    }

    @Test
    fun `principal is written under its stable SerialName`() {
        val encoded = json.encodeToString(EmissionProvenance.serializer(), baseProvenance)

        assertTrue(encoded.contains("\"principal\":{\"type\":\"Principal.Ambient\"}"), encoded)
    }

    @Test
    fun `a payload written before the causal edge decodes as a root under ambient authority`() {
        val current = json.encodeToJsonElement(Emission.serializer(), rootEmission()).jsonObject
        val legacyProvenance = JsonObject(
            current.getValue("provenance").jsonObject - "parentEmissionId" - "principal",
        )
        val legacy = JsonObject(current + ("provenance" to legacyProvenance))

        val decoded = json.decodeFromJsonElement(Emission.serializer(), legacy)

        assertNull(decoded.provenance.parentEmissionId)
        assertEquals(Principal.Ambient, decoded.provenance.principal)
        assertEquals("run-1", decoded.provenance.runId)
        assertEquals("abcdef0123456789", decoded.provenance.inputDigest)
    }

    @Test
    fun `a root still writes both keys when the Json skips defaults`() {
        // A consumer's Json need not encode defaults. The edge and the principal are written
        // anyway, so a root's explicit null stays distinguishable from a pre-edge payload.
        val sparse = Json { encodeDefaults = false }

        val provenance = sparse.encodeToJsonElement(Emission.serializer(), rootEmission())
            .jsonObject
            .getValue("provenance")
            .jsonObject

        assertEquals(JsonNull, provenance["parentEmissionId"])
        assertEquals(JsonPrimitive("Principal.Ambient"), provenance["principal"]?.jsonObject?.get("type"))
        assertNull(provenance["plugId"], "ordinary defaults are still skipped")
    }

    private fun rootEmission(): Emission = emission(
        payload = EmissionPayload.Prose(text = "x", format = ProseFormat.PLAIN),
        kind = EmissionKind.Prose,
    ).let { it.copy(provenance = it.provenance.copy(parentEmissionId = null, plugId = null)) }
}
