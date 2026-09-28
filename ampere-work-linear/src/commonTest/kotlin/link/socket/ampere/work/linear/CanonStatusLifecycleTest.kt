package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.link.LinkId

/**
 * The AMPR-314 round trip, end to end through the work source rather than
 * through the mapping table: write a canonical status, read it back, get the
 * same status.
 *
 * `SupervisoryStatusMappingTest` pins the table; this pins the table against the
 * provider's *measured* behaviour. The distinction is the one that matters here,
 * because a supervisory status is composed of two or three writes against two
 * different objects, and a table can agree with itself while the protocol that
 * realises it does not.
 */
class CanonStatusLifecycleTest {

    private val linkId = LinkId("work-source-link")
    private val instance = SupervisorInstanceId("supervisor-a")

    private fun seeded(
        status: String = WorkSourceStates.TODO,
        statusType: WorkItemStatusType = WorkItemStatusType.UNSTARTED,
        labels: MutableList<String> = mutableListOf(WAVE),
    ): FakeWorkSource = FakeWorkSource().apply {
        add(
            FakeWorkSource.Issue(
                identifier = ISSUE,
                status = status,
                statusType = statusType,
                labels = labels,
            ),
        )
    }

    private fun workSource(fake: FakeWorkSource) =
        LinearWorkSource(tools = fake, linkId = linkId, instanceId = instance)

    @Test
    fun `every canon status survives a write and a read through the work source`() = runTest {
        val fake = seeded()
        val supervisor = workSource(fake)

        // Cumulative on one ticket, in lifecycle order — the sequence a supervisor
        // actually walks, rather than nine independent fixtures.
        CanonWorkStatus.entries.forEach { status ->
            when (status) {
                // The two whose expression is a protocol. markStatus refuses both;
                // these are the entry points it points at.
                CanonWorkStatus.CLAIMED -> supervisor.claim(ISSUE).getOrThrow()
                CanonWorkStatus.ESCALATED ->
                    supervisor.escalate(ISSUE, "the gate needs a decision a machine must not make")
                        .getOrThrow()

                else -> supervisor.markStatus(ISSUE, status).getOrThrow()
            }

            assertEquals(
                status,
                supervisor.readCanonWorkItem(ISSUE).getOrThrow().status,
                "$status did not read back as itself",
            )
        }
    }

    @Test
    fun `a claim is what makes a claimed ticket readable`() = runTest {
        val fake = seeded(status = WorkSourceStates.IN_PROGRESS, statusType = WorkItemStatusType.STARTED)
        val supervisor = workSource(fake)

        // Same provider state either side of the claim: the ticket is already in
        // the in-progress state, so the comment is the entire difference.
        assertEquals(
            CanonWorkStatus.IN_PROGRESS,
            supervisor.readCanonWorkItem(ISSUE).getOrThrow().status,
            "a ticket a human moved with no claim behind it must not claim a claimant",
        )

        supervisor.claim(ISSUE).getOrThrow()

        assertEquals(WorkSourceStates.IN_PROGRESS, fake.issue(ISSUE).status)
        assertEquals(CanonWorkStatus.CLAIMED, supervisor.readCanonWorkItem(ISSUE).getOrThrow().status)
    }

    @Test
    fun `a claim by another instance still reads as claimed`() = runTest {
        // The question readCanonWorkItem answers is *is this claimed*, not *do I
        // hold it*. Only the claim protocol decides the second.
        val fake = seeded()
        workSource(fake).claim(ISSUE).getOrThrow()

        val other = LinearWorkSource(fake, linkId, SupervisorInstanceId("supervisor-b"))

        assertEquals(CanonWorkStatus.CLAIMED, other.readCanonWorkItem(ISSUE).getOrThrow().status)
    }

    @Test
    fun `markStatus refuses the statuses whose expression is a protocol`() = runTest {
        val fake = seeded()
        val supervisor = workSource(fake)

        SupervisoryStatusMapping.PROTOCOL_STATUSES.forEach { (status, entryPoint) ->
            val cause = assertIs<WorkSourceException>(supervisor.markStatus(ISSUE, status).exceptionOrNull())
            val refusal = assertIs<WorkSourceFailure.StatusNeedsProtocol>(cause.failure)

            assertEquals(status, refusal.status)
            assertEquals(entryPoint, refusal.use)
            assertTrue(entryPoint in refusal.describe(), refusal.describe())
        }

        assertEquals(
            emptyList(),
            fake.callsTo(WorkSourceToolPins.SAVE_ISSUE),
            "a refused status must write nothing at all",
        )
    }

    @Test
    fun `finishing a gated ticket clears the gate and leaves the rest alone`() = runTest {
        val fake = seeded(labels = mutableListOf(WAVE, "api"))
        val supervisor = workSource(fake)

        supervisor.requestVerdict(ISSUE).getOrThrow()
        supervisor.escalate(ISSUE, "and then it needed a human too").getOrThrow()
        assertEquals(WorkSourceLabels.GATES, fake.issue(ISSUE).labels.toSet() - setOf(WAVE, "api"))

        supervisor.markStatus(ISSUE, CanonWorkStatus.DONE).getOrThrow()

        assertEquals(
            listOf(WAVE, "api"),
            fake.issue(ISSUE).labels,
            "a status write edits gates and nothing else",
        )
        assertEquals(CanonWorkStatus.DONE, supervisor.readCanonWorkItem(ISSUE).getOrThrow().status)
    }

    @Test
    fun `the transition lands before the gate is cleared`() = runTest {
        // A window where the ticket has moved but is still gated reads as stopped
        // — safe. The reverse reads as ready.
        val fake = seeded(labels = mutableListOf(WAVE, WorkSourceLabels.GATE_AWAITING_VERDICT))
        val supervisor = workSource(fake)
        fake.calls.clear()

        supervisor.markStatus(ISSUE, CanonWorkStatus.DONE).getOrThrow()

        val writes = fake.callsTo(WorkSourceToolPins.SAVE_ISSUE)
        assertEquals(WorkSourceStates.DONE, writes.first().arguments.text("state"))
        assertEquals(
            listOf(WorkSourceLabels.GATE_AWAITING_VERDICT),
            writes.last().arguments.texts("removeLabels"),
        )
    }

    @Test
    fun `a stopped target is written without a read`() = runTest {
        val fake = seeded()

        workSource(fake).requestVerdict(ISSUE).getOrThrow()

        assertEquals(
            emptyList(),
            fake.callsTo(WorkSourceToolPins.GET_ISSUE),
            "a stop cannot clear a gate so it has no reason to look at the labels",
        )
        assertEquals(
            listOf(WorkSourceLabels.GATE_AWAITING_VERDICT),
            fake.callsTo(WorkSourceToolPins.SAVE_ISSUE).last().arguments.texts("addLabels"),
        )
    }

    @Test
    fun `the comment scan is paid for only where it can change the answer`() = runTest {
        val gated = seeded(labels = mutableListOf(WAVE, WorkSourceLabels.GATE_AWAITING_VERDICT))
        workSource(gated).readCanonWorkItem(ISSUE).getOrThrow()

        assertEquals(
            emptyList(),
            gated.callsTo(WorkSourceToolPins.LIST_COMMENTS),
            "a gated ticket cannot be CLAIMED whatever the comments say",
        )

        val working = seeded(status = WorkSourceStates.IN_PROGRESS, statusType = WorkItemStatusType.STARTED)
        workSource(working).readCanonWorkItem(ISSUE).getOrThrow()

        assertEquals(1, working.callsTo(WorkSourceToolPins.LIST_COMMENTS).size)
    }

    @Test
    fun `an escalated ticket keeps the state the work stopped in`() = runTest {
        val fake = seeded(status = WorkSourceStates.IN_PROGRESS, statusType = WorkItemStatusType.STARTED)
        val supervisor = workSource(fake)

        supervisor.escalate(ISSUE, "ran out of rope").getOrThrow()

        assertEquals(
            WorkSourceStates.IN_PROGRESS,
            fake.issue(ISSUE).status,
            "where the work stopped is the most useful fact an escalated ticket carries",
        )
        assertEquals(CanonWorkStatus.ESCALATED, supervisor.readCanonWorkItem(ISSUE).getOrThrow().status)
    }

    @Test
    fun `a canon read carries the provenance and the provider status too`() = runTest {
        val fake = seeded(status = WorkSourceStates.IN_REVIEW, statusType = WorkItemStatusType.STARTED)

        val item = workSource(fake).readCanonWorkItem(ISSUE).getOrThrow()

        assertEquals(CanonWorkStatus.VERIFYING, item.status)
        assertEquals(
            WorkSourceStates.IN_REVIEW,
            item.providerStatus,
            "providerStatus stays verbatim; it is no longer where the lifecycle lives",
        )
        assertEquals(linkId, item.provenance.sourceHandle.linkId)
        assertEquals(ISSUE, item.provenance.sourceHandle.nativeId)
    }

    private companion object {
        const val ISSUE = "AMPR-101"
        const val WAVE = "wave:w0"
    }
}
