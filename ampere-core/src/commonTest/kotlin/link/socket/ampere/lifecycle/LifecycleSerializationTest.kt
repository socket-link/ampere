package link.socket.ampere.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProse

/**
 * The lifecycle types are written down by one process and read back by
 * another — a gate outlives whatever raised it. Like `CanonSerializationTest`,
 * the wire names are pinned here as literal strings so a rename fails loudly
 * instead of silently stranding every gate and register already recorded.
 */
class LifecycleSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val subject = CanonId("SCKT-664")
    private val raisedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val later = Instant.fromEpochMilliseconds(1_700_000_600_000)

    private val evidence = ReconEvidence(
        source = "ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/qa/QualityState.kt",
        observedAt = raisedAt,
        revision = "4bc0eb27014ab05c79171540d325fbcca4afccc2",
        locator = "226",
        excerpt = CanonProse.bounded("data class Finding("),
    )

    private fun findingSamples(): Map<String, ReconFinding> = mapOf(
        "verified" to ReconFinding(
            id = ReconFindingId("R5-1"),
            claim = "Finding is already taken",
            confidence = ReconConfidence.VERIFIED,
            evidence = listOf(evidence),
            subject = subject,
        ),
        "inferred" to ReconFinding(
            id = ReconFindingId("R5-2"),
            claim = "A second Finding would collide in one artifact",
            confidence = ReconConfidence.INFERRED,
            evidence = listOf(evidence),
        ),
        "untested" to ReconFinding(
            id = ReconFindingId("R5-3"),
            claim = "Nothing downstream depends on the name",
            confidence = ReconConfidence.UNTESTED,
        ),
    )

    private val options = listOf(
        DecisionOption(key = "A", summary = "Cut the types in Ampere"),
        DecisionOption(key = "B", summary = "Declare them in Socket"),
    )

    private val proposed = LifecycleDecision(
        id = DecisionId("L8"),
        question = "Who owns the work-decomposition types?",
        options = options,
        defaultOption = "A",
    )

    private val locked = proposed.copy(
        id = DecisionId("L9"),
        chosenOption = "B",
        lock = DecisionLock(lockedAt = later, lockedBy = Principal.Ambient),
    )

    private fun gateSamples(): Map<String, LifecycleGate> = mapOf(
        "lifecycle.gate.open" to LifecycleGate.Open(subject, name = "stop-gate", raisedAt = raisedAt),
        "lifecycle.gate.awaiting_person" to LifecycleGate.AwaitingPerson(
            subject,
            name = "stop-gate",
            raisedAt = raisedAt,
            waitingSince = later,
            waitingFor = "lock the decision register",
        ),
        "lifecycle.gate.closed" to LifecycleGate.Closed(
            subject,
            name = "stop-gate",
            raisedAt = raisedAt,
            outcome = GateOutcome.REJECTED,
            closedAt = later,
            closedBy = Principal.Ambient,
            note = "L8 inventory is wrong in both directions",
        ),
    )

    /** Exhaustive on purpose: a fourth gate state breaks this until it has a pinned name. */
    private fun pinnedDiscriminator(gate: LifecycleGate): String = when (gate) {
        is LifecycleGate.Open -> "lifecycle.gate.open"
        is LifecycleGate.AwaitingPerson -> "lifecycle.gate.awaiting_person"
        is LifecycleGate.Closed -> "lifecycle.gate.closed"
    }

    // -- finding -----------------------------------------------------------

    @Test
    fun `every finding round-trips with its confidence label`() {
        findingSamples().forEach { (label, finding) ->
            val encoded = json.encodeToString(ReconFinding.serializer(), finding)
            val decoded = json.decodeFromString(ReconFinding.serializer(), encoded)

            assertEquals(finding, decoded, "round-trip changed the $label finding")
            assertTrue(encoded.contains("\"confidence\":\"$label\""), "expected label $label; got $encoded")
        }
    }

    @Test
    fun `the finding samples cover every confidence label`() {
        assertEquals(
            ReconConfidence.entries.toSet(),
            findingSamples().values.map { it.confidence }.toSet(),
            "a confidence label has no serialization sample; drift in it would go unnoticed",
        )
    }

    @Test
    fun `confidence wire names are stable`() {
        val wireNames = ReconConfidence.entries.associateWith {
            json.encodeToString(ReconConfidence.serializer(), it)
        }

        assertEquals(
            mapOf(
                ReconConfidence.VERIFIED to "\"verified\"",
                ReconConfidence.INFERRED to "\"inferred\"",
                ReconConfidence.UNTESTED to "\"untested\"",
            ),
            wireNames,
        )
    }

    @Test
    fun `evidence survives serialization intact`() {
        val decoded = json.decodeFromString(
            ReconFinding.serializer(),
            json.encodeToString(ReconFinding.serializer(), findingSamples().getValue("verified")),
        )

        assertEquals(evidence, decoded.evidence.single())
        assertEquals(raisedAt, decoded.evidence.single().observedAt)
    }

    @Test
    fun `a verified finding with no evidence still decodes`() {
        // No init guard by design: the unbacked label must be representable so
        // it can be recorded and then caught. See ReconFinding's KDoc.
        val unbacked = ReconFinding(
            id = ReconFindingId("R5-4"),
            claim = "Trust me",
            confidence = ReconConfidence.VERIFIED,
        )

        val decoded = json.decodeFromString(
            ReconFinding.serializer(),
            json.encodeToString(ReconFinding.serializer(), unbacked),
        )

        assertEquals(unbacked, decoded)
        assertFalse(decoded.isSubstantiated)
    }

    @Test
    fun `a finding with only its required keys decodes`() {
        val encoded = json.encodeToJsonElement(
            ReconFinding.serializer(),
            findingSamples().getValue("untested"),
        ).jsonObject
        val minimal = JsonObject(encoded - "evidence" - "subject")

        val decoded = json.decodeFromJsonElement(ReconFinding.serializer(), minimal)

        assertEquals(emptyList(), decoded.evidence)
        assertNull(decoded.subject)
    }

    // -- decision and register ---------------------------------------------

    @Test
    fun `a proposed decision round-trips with nothing chosen and no lock`() {
        val encoded = json.encodeToString(LifecycleDecision.serializer(), proposed)
        val decoded = json.decodeFromString(LifecycleDecision.serializer(), encoded)

        assertEquals(proposed, decoded)
        assertNull(decoded.chosenOption)
        assertNull(decoded.lock)
        assertEquals("A", decoded.effectiveOption)
    }

    @Test
    fun `a locked decision round-trips with its lock`() {
        val encoded = json.encodeToString(LifecycleDecision.serializer(), locked)
        val decoded = json.decodeFromString(LifecycleDecision.serializer(), encoded)

        assertEquals(locked, decoded)
        assertEquals(later, decoded.lock?.lockedAt)
        assertEquals(Principal.Ambient, decoded.lock?.lockedBy)
        assertTrue(encoded.contains("\"lockedBy\":{\"type\":\"Principal.Ambient\"}"), encoded)
    }

    @Test
    fun `a register round-trips and keeps its decisions in order`() {
        val register = DecisionRegister(subject = subject, decisions = listOf(locked, proposed))

        val encoded = json.encodeToString(DecisionRegister.serializer(), register)
        val decoded = json.decodeFromString(DecisionRegister.serializer(), encoded)

        assertEquals(register, decoded)
        assertEquals(listOf(DecisionId("L9"), DecisionId("L8")), decoded.decisions.map { it.id })
    }

    @Test
    fun `an incoherent decision can be constructed and recorded`() {
        val dangling = proposed.copy(defaultOption = "Z")

        val decoded = json.decodeFromString(
            LifecycleDecision.serializer(),
            json.encodeToString(LifecycleDecision.serializer(), dangling),
        )

        assertEquals(dangling, decoded)
        assertFalse(decoded.isCoherent)
    }

    @Test
    fun `a decision written by a newer producer decodes past its unknown key`() {
        val encoded = json.encodeToJsonElement(LifecycleDecision.serializer(), proposed).jsonObject
        val newer = JsonObject(encoded + ("supersedes" to JsonPrimitive("L3")))

        assertEquals(proposed, json.decodeFromJsonElement(LifecycleDecision.serializer(), newer))
    }

    // -- gate --------------------------------------------------------------

    @Test
    fun `every gate state round-trips through the sealed serializer`() {
        gateSamples().forEach { (discriminator, gate) ->
            val encoded = json.encodeToString(LifecycleGate.serializer(), gate)
            val decoded = json.decodeFromString(LifecycleGate.serializer(), encoded)

            assertEquals(gate, decoded, "round-trip changed $discriminator")
        }
    }

    @Test
    fun `every gate state writes its pinned discriminator`() {
        gateSamples().forEach { (discriminator, gate) ->
            val encoded = json.encodeToString(LifecycleGate.serializer(), gate)

            assertEquals(discriminator, pinnedDiscriminator(gate))
            assertTrue(
                encoded.contains("\"type\":\"$discriminator\""),
                "expected discriminator $discriminator; got $encoded",
            )
        }
    }

    @Test
    fun `a closed gate keeps its outcome and when it was raised`() {
        val decoded = json.decodeFromString(
            LifecycleGate.serializer(),
            json.encodeToString(LifecycleGate.serializer(), gateSamples().getValue("lifecycle.gate.closed")),
        )

        assertIs<LifecycleGate.Closed>(decoded)
        assertEquals(GateOutcome.REJECTED, decoded.outcome)
        assertEquals(raisedAt, decoded.raisedAt)
        assertEquals(later, decoded.closedAt)
    }

    @Test
    fun `gate outcome wire names are stable`() {
        val wireNames = GateOutcome.entries.associateWith {
            json.encodeToString(GateOutcome.serializer(), it)
        }

        assertEquals(
            mapOf(
                GateOutcome.PASSED to "\"passed\"",
                GateOutcome.REJECTED to "\"rejected\"",
                GateOutcome.WITHDRAWN to "\"withdrawn\"",
            ),
            wireNames,
        )
    }

    // -- wire names --------------------------------------------------------

    @Test
    fun `the lifecycle serial names are pinned`() {
        assertEquals("lifecycle.recon_finding", ReconFinding.serializer().descriptor.serialName)
        assertEquals("lifecycle.recon_evidence", ReconEvidence.serializer().descriptor.serialName)
        assertEquals("lifecycle.decision", LifecycleDecision.serializer().descriptor.serialName)
        assertEquals("lifecycle.decision_option", DecisionOption.serializer().descriptor.serialName)
        assertEquals("lifecycle.decision_lock", DecisionLock.serializer().descriptor.serialName)
        assertEquals("lifecycle.decision_register", DecisionRegister.serializer().descriptor.serialName)
    }
}
