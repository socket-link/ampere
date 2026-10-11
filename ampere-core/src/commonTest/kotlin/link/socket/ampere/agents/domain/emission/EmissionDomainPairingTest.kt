package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.domain.Principal

/**
 * `Emission.init` checks the kind/payload pairing for domain kinds only; the
 * four core kinds are left as they were so no existing producer is rejected.
 */
class EmissionDomainPairingTest {

    private val provenance = EmissionProvenance(
        inputDigest = "irrelevant",
        parentEmissionId = null,
        principal = Principal.Ambient,
    )

    private fun domainPayload(kindId: String) = EmissionPayload.Domain(
        kindId = kindId,
        schemaVersion = 1,
        body = JsonPrimitive("body"),
        fallbackText = "fallback",
    )

    private fun emission(kind: EmissionKind, payload: EmissionPayload) = Emission(
        id = "id",
        kind = kind,
        payload = payload,
        provenance = provenance,
        producedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `a Domain kind with a Prose payload is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            emission(EmissionKind.Domain("recipe"), EmissionPayload.Prose("x", ProseFormat.PLAIN))
        }
        assertTrue(failure.message!!.contains("recipe"), failure.message)
    }

    @Test
    fun `a Domain payload with a Prose kind is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            emission(EmissionKind.Prose, domainPayload("recipe"))
        }
    }

    @Test
    fun `mismatched domain ids are rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            emission(EmissionKind.Domain("recipe"), domainPayload("meal-plan"))
        }
        assertTrue(failure.message!!.contains("meal-plan"), failure.message)
    }

    @Test
    fun `matching domain ids are accepted`() {
        val emission = emission(EmissionKind.Domain("recipe"), domainPayload("recipe"))
        assertEquals(EmissionKind.Domain("recipe"), emission.kind)
    }

    @Test
    fun `core kind pairing is not enforced`() {
        // Deliberate (AMPR-427 outcome 4): enforcing it could reject existing producers.
        emission(EmissionKind.Prose, EmissionPayload.Sensor("label", "value"))
    }
}
