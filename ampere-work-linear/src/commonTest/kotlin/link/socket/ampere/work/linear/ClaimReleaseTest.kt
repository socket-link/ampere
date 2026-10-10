package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.link.LinkId

/**
 * Releasing a claim: the write half of the startup reconciliation pass's step 4
 * (AMPR-310), run on behalf of a supervisor that is already dead.
 *
 * Three properties carry the weight. **A released claim stops winning races** —
 * without that, reconciliation hands back a ticket whose permanent winner is a
 * process that no longer exists. **It is idempotent** — the pass can be killed
 * between the retraction comment and the status revert, so a second run must finish
 * the revert without commenting twice. And **it never overwrites a human** —
 * ratified rule B4: retract the claim, but leave a state something else set exactly
 * where it is.
 */
class ClaimReleaseTest {

    private val linkId = LinkId("work-source-link")
    private val dead = SupervisorInstanceId("supervisor-dead")
    private val other = SupervisorInstanceId("supervisor-other")
    private val reconciler = SupervisorInstanceId("reconcile-1")

    private fun seeded(): FakeWorkSource = FakeWorkSource().apply {
        add(FakeWorkSource.Issue("AMPR-310", labels = mutableListOf("wave:w2")))
    }

    private fun supervisor(tools: WorkSourceToolCaller, instanceId: SupervisorInstanceId) =
        LinearWorkSource(tools = tools, linkId = linkId, instanceId = instanceId)

    /** The state a crashed supervisor leaves: a live claim and the ticket in progress. */
    private suspend fun crashedHolding(fake: FakeWorkSource): LinearWorkSource {
        supervisor(fake, dead).claim("AMPR-310").getOrThrow()
        return supervisor(fake, reconciler)
    }

    private fun releaseComments(fake: FakeWorkSource): List<SupervisoryComment.Release> =
        fake.comments("AMPR-310").mapNotNull { SupervisoryComment.parse(it.body) as? SupervisoryComment.Release }

    @Test
    fun `releasing a dead claim retracts it and requeues the ticket`() = runTest {
        val fake = seeded()
        val pass = crashedHolding(fake)

        val outcome = pass.release("AMPR-310", dead).getOrThrow()

        val released = assertIs<ReleaseOutcome.Released>(outcome)
        assertTrue(released.commentPosted)
        assertEquals("Todo", released.revertedTo)
        assertNull(released.reason)
        assertEquals("Todo", fake.issue("AMPR-310").status)
        assertEquals(listOf(dead), releaseComments(fake).map { it.instanceId })
    }

    @Test
    fun `the retraction comment is posted before the revert`() = runTest {
        // The mirror of the claim protocol's ordering, and for the mirror reason: the
        // comment is the durable half, so a pass killed in between finds it on the
        // next run and finishes the revert instead of commenting twice.
        val fake = seeded()
        val pass = crashedHolding(fake)
        val before = fake.calls.size

        pass.release("AMPR-310", dead).getOrThrow()

        val writes = fake.calls.drop(before)
            .map { it.tool }
            .filter { it == WorkSourceToolPins.SAVE_COMMENT || it == WorkSourceToolPins.SAVE_ISSUE }
        assertEquals(listOf(WorkSourceToolPins.SAVE_COMMENT, WorkSourceToolPins.SAVE_ISSUE), writes)
    }

    @Test
    fun `a released claim no longer wins the arbitration`() = runTest {
        // The whole reason the retraction has to be machine-readable: comments are
        // append-only, so a dead supervisor's claim is the earliest one forever.
        val fake = seeded()
        crashedHolding(fake).release("AMPR-310", dead).getOrThrow()

        val outcome = supervisor(fake, other).claim("AMPR-310").getOrThrow()

        val won = assertIs<ClaimOutcome.Won>(outcome)
        assertEquals(other, won.claim.instanceId)
        assertEquals("In Progress", fake.issue("AMPR-310").status)
    }

    @Test
    fun `a second release posts nothing and leaves the same end state`() = runTest {
        val fake = seeded()
        val pass = crashedHolding(fake)
        pass.release("AMPR-310", dead).getOrThrow()
        val afterFirst = fake.issue("AMPR-310").status

        val outcome = pass.release("AMPR-310", dead).getOrThrow()

        val released = assertIs<ReleaseOutcome.Released>(outcome)
        assertFalse(released.commentPosted, "the read-back must find the first run's release comment")
        assertEquals(1, releaseComments(fake).size)
        assertEquals(afterFirst, fake.issue("AMPR-310").status)
        assertEquals("Todo", fake.issue("AMPR-310").status)
    }

    @Test
    fun `a pass killed after the comment still finishes the revert`() = runTest {
        // Exactly the interrupted-mid-release state: the retraction is on the ticket
        // and the status was never moved.
        val fake = seeded()
        val pass = crashedHolding(fake)
        fake.addComment("AMPR-310", SupervisoryComment.Release("AMPR-310", dead).render())
        assertEquals("In Progress", fake.issue("AMPR-310").status)

        val outcome = pass.release("AMPR-310", dead).getOrThrow()

        val released = assertIs<ReleaseOutcome.Released>(outcome)
        assertFalse(released.commentPosted)
        assertEquals("Todo", released.revertedTo)
        assertEquals("Todo", fake.issue("AMPR-310").status)
        assertEquals(1, releaseComments(fake).size)
    }

    @Test
    fun `a ticket a human moved out of flight is deferred and never overwritten`() = runTest {
        val fake = seeded()
        val pass = crashedHolding(fake)
        // Somebody accepted the work the orphaned agent pushed.
        supervisor(fake, other).transition("AMPR-310", "Done").getOrThrow()

        val outcome = pass.release("AMPR-310", dead).getOrThrow()

        val deferred = assertIs<ReleaseOutcome.Deferred>(outcome)
        assertEquals("Done", deferred.observedState)
        val interference = assertIs<ReleaseInterference.MovedOutOfFlight>(deferred.interference)
        assertEquals("Done", interference.observed)
        assertEquals("Done", fake.issue("AMPR-310").status, "the human state survives the pass")
        assertTrue(deferred.commentPosted, "the claim is still unambiguously not held")
    }

    @Test
    fun `a state the claim did not set is deferred`() = runTest {
        // The ticket was already in progress when the claim landed, so this claim's
        // transition is not the write a revert would be undoing.
        val fake = seeded()
        supervisor(fake, other).transition("AMPR-310", "In Progress").getOrThrow()
        fake.addComment("AMPR-310", SupervisoryComment.Claim("AMPR-310", dead).render())

        val outcome = supervisor(fake, reconciler).release("AMPR-310", dead).getOrThrow()

        val deferred = assertIs<ReleaseOutcome.Deferred>(outcome)
        val interference = assertIs<ReleaseInterference.StateNotSetByClaim>(deferred.interference)
        assertEquals("In Progress", interference.state)
        assertTrue(interference.since!! < interference.claimedAt)
        assertEquals("In Progress", fake.issue("AMPR-310").status)
    }

    @Test
    fun `a dispatch that reached verification is still requeued`() = runTest {
        // In Review is the supervisor's own next state, not a human's intervention,
        // so a crash there is ordinary residue rather than interference.
        val fake = seeded()
        val pass = crashedHolding(fake)
        supervisor(fake, dead).transition("AMPR-310", "In Review").getOrThrow()

        val outcome = pass.release("AMPR-310", dead).getOrThrow()

        assertEquals("Todo", assertIs<ReleaseOutcome.Released>(outcome).revertedTo)
        assertEquals("Todo", fake.issue("AMPR-310").status)
    }

    @Test
    fun `no state history means no revert`() = runTest {
        val fake = seeded()
        val pass = crashedHolding(fake)
        fake.omitStateHistory = true

        val outcome = pass.release("AMPR-310", dead).getOrThrow()

        val deferred = assertIs<ReleaseOutcome.Deferred>(outcome)
        assertIs<ReleaseInterference.HistoryUnavailable>(deferred.interference)
        assertEquals("In Progress", fake.issue("AMPR-310").status)
    }

    @Test
    fun `releasing a claim this instance never made touches nothing`() = runTest {
        val fake = seeded()
        supervisor(fake, other).claim("AMPR-310").getOrThrow()
        val before = fake.issue("AMPR-310").status

        val outcome = supervisor(fake, reconciler).release("AMPR-310", dead).getOrThrow()

        val notHeld = assertIs<ReleaseOutcome.NotHeld>(outcome)
        assertEquals(other, notHeld.liveHolder)
        assertEquals(before, fake.issue("AMPR-310").status)
        assertTrue(releaseComments(fake).isEmpty())
    }

    @Test
    fun `an unclaimed ticket reports no holder`() = runTest {
        val fake = seeded()

        val outcome = supervisor(fake, reconciler).release("AMPR-310", dead).getOrThrow()

        assertNull(assertIs<ReleaseOutcome.NotHeld>(outcome).liveHolder)
        assertEquals("Todo", fake.issue("AMPR-310").status)
    }

    @Test
    fun `another instance's live claim keeps the ticket where it is`() = runTest {
        // The dead instance won, and a second instance is mid-claim: its comment is
        // posted and it has not arbitrated yet. The dead claim still has to be
        // retracted, but the ticket is not this pass's to requeue — the moment that
        // second instance reads the order it will find itself the holder.
        val fake = seeded()
        val pass = crashedHolding(fake)
        fake.addComment("AMPR-310", SupervisoryComment.Claim("AMPR-310", other).render())

        val outcome = pass.release("AMPR-310", dead).getOrThrow()

        val released = assertIs<ReleaseOutcome.Released>(outcome)
        assertTrue(released.commentPosted)
        assertNull(released.revertedTo)
        val reason = assertNotNull(released.reason)
        assertTrue(reason.contains(other.value), reason)
        assertEquals("In Progress", fake.issue("AMPR-310").status)
    }

    @Test
    fun `a release only retracts its own instance's claim`() = runTest {
        val fake = seeded()
        fake.addComment("AMPR-310", SupervisoryComment.Claim("AMPR-310", other).render())
        fake.addComment("AMPR-310", SupervisoryComment.Claim("AMPR-310", dead).render())
        fake.addComment("AMPR-310", SupervisoryComment.Release("AMPR-310", dead).render())

        val live = fake.comments("AMPR-310").liveClaimsFor("AMPR-310")

        assertEquals(listOf(other), live.map { it.instanceId })
    }

    @Test
    fun `a claim posted after a release is live again`() = runTest {
        // A release retracts only the claims it follows in the total order, so a
        // re-claim is not retroactively cancelled by an older release.
        val fake = seeded()
        fake.addComment("AMPR-310", SupervisoryComment.Claim("AMPR-310", dead).render())
        fake.addComment("AMPR-310", SupervisoryComment.Release("AMPR-310", dead).render())
        fake.addComment("AMPR-310", SupervisoryComment.Claim("AMPR-310", dead).render())

        val live = fake.comments("AMPR-310").liveClaimsFor("AMPR-310")

        assertEquals(listOf(dead), live.map { it.instanceId })
    }

    @Test
    fun `an already queued ticket is retracted without a transition`() = runTest {
        val fake = seeded()
        fake.addComment("AMPR-310", SupervisoryComment.Claim("AMPR-310", dead).render())

        val outcome = supervisor(fake, reconciler).release("AMPR-310", dead).getOrThrow()

        val released = assertIs<ReleaseOutcome.Released>(outcome)
        assertTrue(released.commentPosted)
        assertNull(released.revertedTo)
        assertTrue(fake.transitions().isEmpty(), "nothing needed moving")
    }
}
