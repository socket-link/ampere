package link.socket.ampere.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.canon.CanonId
import link.socket.ampere.probe.FreshnessProbe
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict

/**
 * The rules the lifecycle types state in KDoc, pinned: what a label obliges,
 * what a lock freezes, and which gate states release downstream work.
 */
class LifecycleTypesTest {

    private val subject = CanonId("SCKT-664")
    private val raisedAt = Instant.parse("2026-09-27T09:00:00Z")
    private val later = Instant.parse("2026-09-27T17:00:00Z")

    private val evidence = ReconEvidence(source = "QualityState.kt", observedAt = raisedAt, locator = "226")

    private fun finding(
        confidence: ReconConfidence,
        evidence: List<ReconEvidence> = emptyList(),
    ): ReconFinding = ReconFinding(
        id = ReconFindingId("R5-1"),
        claim = "Finding is already taken",
        confidence = confidence,
        evidence = evidence,
    )

    private val decision = LifecycleDecision(
        id = DecisionId("L8"),
        question = "Who owns the work-decomposition types?",
        options = listOf(
            DecisionOption(key = "A", summary = "Cut the types in Ampere"),
            DecisionOption(key = "B", summary = "Declare them in Socket"),
        ),
        defaultOption = "A",
    )

    private val open = LifecycleGate.Open(subject, name = "stop-gate", raisedAt = raisedAt)

    // -- finding -----------------------------------------------------------

    @Test
    fun `only a verified finding is obliged to name evidence`() {
        assertFalse(finding(ReconConfidence.VERIFIED).isSubstantiated)
        assertTrue(finding(ReconConfidence.VERIFIED, listOf(evidence)).isSubstantiated)
        assertTrue(finding(ReconConfidence.INFERRED).isSubstantiated)
        assertTrue(finding(ReconConfidence.UNTESTED).isSubstantiated)
    }

    @Test
    fun `a freshness probe runs over the evidence of a finding`() = runTest {
        // The compose-not-merge claim in ReconFinding's KDoc: a Probe tests a
        // finding's evidence and returns a Verdict about it.
        val probe = FreshnessProbe(maxAge = 1.days, now = { later })
        val stale = evidence.copy(observedAt = later - 30.days)

        assertIs<Verdict.Holds>(probe.evaluate(evidence))

        val verdict = probe.evaluate(stale)
        assertIs<Verdict.Undetermined>(verdict)
        assertEquals(UndeterminedCause.STALE, verdict.cause)
    }

    // -- decision ----------------------------------------------------------

    @Test
    fun `an unchosen decision resolves to its default`() {
        assertNull(decision.chosenOption)
        assertEquals("A", decision.effectiveOption)
    }

    @Test
    fun `a chosen option outranks the default and can be re-chosen`() {
        val chosen = decision.choose("B").getOrThrow()
        assertEquals("B", chosen.effectiveOption)

        assertEquals("A", chosen.choose("A").getOrThrow().effectiveOption)
    }

    @Test
    fun `choosing an option the decision does not have fails`() {
        assertTrue(decision.choose("Z").isFailure)
    }

    @Test
    fun `locking with nothing chosen records that nobody chose`() {
        val locked = decision.lock(at = later, by = Principal.Ambient).getOrThrow()

        assertTrue(locked.isLocked)
        assertNull(locked.chosenOption)
        assertEquals("A", locked.effectiveOption)
        assertEquals(DecisionLock(lockedAt = later, lockedBy = Principal.Ambient), locked.lock)
    }

    @Test
    fun `a locked decision refuses a new choice and a second lock`() {
        val locked = decision.lock(at = later, by = Principal.Ambient).getOrThrow()

        assertTrue(locked.choose("B").isFailure)
        assertTrue(locked.lock(at = later + 1.hours, by = Principal.Ambient).isFailure)
    }

    @Test
    fun `a decision whose default names no option is incoherent and will not lock`() {
        val dangling = decision.copy(defaultOption = "Z")

        assertFalse(dangling.isCoherent)
        assertTrue(dangling.lock(at = later, by = Principal.Ambient).isFailure)
    }

    @Test
    fun `duplicate option keys make a decision incoherent`() {
        val duplicated = decision.copy(options = decision.options + DecisionOption(key = "A", summary = "Again"))

        assertTrue(decision.isCoherent)
        assertFalse(duplicated.isCoherent)
    }

    // -- register ----------------------------------------------------------

    @Test
    fun `an empty register is not locked`() {
        assertFalse(DecisionRegister(subject = subject).isLocked)
    }

    @Test
    fun `a register is locked only when every decision is`() {
        val second = decision.copy(id = DecisionId("L9"))
        val partly = DecisionRegister(
            subject = subject,
            decisions = listOf(decision.lock(at = later, by = Principal.Ambient).getOrThrow(), second),
        )

        assertFalse(partly.isLocked)
        assertEquals(listOf(DecisionId("L9")), partly.unlocked.map { it.id })
        assertEquals(second, partly[DecisionId("L9")])
        assertNull(partly[DecisionId("L10")])
    }

    @Test
    fun `locking a register locks the open decisions and keeps existing locks`() {
        val early = decision.lock(at = raisedAt, by = Principal.Ambient).getOrThrow()
        val register = DecisionRegister(
            subject = subject,
            decisions = listOf(early, decision.copy(id = DecisionId("L9"))),
        )

        val locked = register.lock(at = later, by = Principal.Ambient).getOrThrow()

        assertTrue(locked.isLocked)
        assertEquals(listOf(raisedAt, later), locked.decisions.map { it.lock?.lockedAt })
    }

    @Test
    fun `a register with one decision that cannot lock locks nothing`() {
        val register = DecisionRegister(
            subject = subject,
            decisions = listOf(decision, decision.copy(id = DecisionId("L9"), defaultOption = "Z")),
        )

        assertFalse(register.isCoherent)
        assertTrue(register.lock(at = later, by = Principal.Ambient).isFailure)
        assertEquals(2, register.unlocked.size)
    }

    @Test
    fun `a register carrying one id twice is incoherent and will not lock`() {
        val register = DecisionRegister(subject = subject, decisions = listOf(decision, decision))

        assertFalse(register.isCoherent)
        assertTrue(register.lock(at = later, by = Principal.Ambient).isFailure)
    }

    // -- gate --------------------------------------------------------------

    @Test
    fun `an open gate blocks downstream work`() {
        assertTrue(open.blocksDownstream)
    }

    @Test
    fun `a gate waiting on a person blocks and remembers when it was raised`() {
        val waiting = open.awaitPerson(since = later, waitingFor = "lock the decision register")

        assertTrue(waiting.blocksDownstream)
        assertEquals(raisedAt, waiting.raisedAt)
        assertEquals(later, waiting.waitingSince)
    }

    @Test
    fun `only a passed gate releases downstream work`() {
        val waiting = open.awaitPerson(since = later, waitingFor = "lock the decision register")

        val released = GateOutcome.entries.filterNot { outcome ->
            waiting.close(outcome, at = later, by = Principal.Ambient).getOrThrow().blocksDownstream
        }

        assertEquals(listOf(GateOutcome.PASSED), released)
    }

    @Test
    fun `a gate can be withdrawn before anyone was asked`() {
        val closed = open.close(GateOutcome.WITHDRAWN, at = later, by = Principal.Ambient).getOrThrow()

        assertEquals(GateOutcome.WITHDRAWN, closed.outcome)
        assertEquals(raisedAt, closed.raisedAt)
        assertTrue(closed.blocksDownstream)
    }

    @Test
    fun `a closed gate refuses to be closed again`() {
        val rejected = open.close(GateOutcome.REJECTED, at = later, by = Principal.Ambient).getOrThrow()

        assertTrue(rejected.close(GateOutcome.PASSED, at = later, by = Principal.Ambient).isFailure)
    }
}
