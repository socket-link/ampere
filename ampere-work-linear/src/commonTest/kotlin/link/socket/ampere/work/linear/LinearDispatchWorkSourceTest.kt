package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.agents.execution.dispatch.ClaimRelease
import link.socket.ampere.agents.execution.dispatch.DispatchClaim
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.link.LinkId

/**
 * The binding between this work source and the Switchboard's `DispatchWorkSource`
 * port (AMPR-308).
 *
 * Two things are under test: that each port operation is the adapter protocol it
 * names, and that — driven through every operation, over gated and research tickets
 * alike — nothing on the wire removes a label or moves a ticket to a closed state.
 * That second property is the Switchboard's trust boundary, checked where the writes
 * actually leave.
 */
class LinearDispatchWorkSourceTest {

    private val linkId = LinkId("work-source-link")
    private val instance = SupervisorInstanceId("switchboard-1")

    private fun port(fake: FakeWorkSource, instanceId: SupervisorInstanceId = instance) =
        LinearDispatchWorkSource(LinearWorkSource(tools = fake, linkId = linkId, instanceId = instanceId))

    private fun started(id: String, status: String = "In Progress", vararg labels: String) =
        FakeWorkSource.Issue(
            id,
            status = status,
            statusType = WorkItemStatusType.STARTED,
            labels = mutableListOf(*labels),
        )

    private fun done(id: String, vararg labels: String) =
        FakeWorkSource.Issue(
            id,
            status = "Done",
            statusType = WorkItemStatusType.COMPLETED,
            labels = mutableListOf(*labels),
        )

    private fun queued(id: String, vararg labels: String) =
        FakeWorkSource.Issue(id, labels = mutableListOf("wave:w1", *labels))

    @Test
    fun `the ready candidates are the adapter's ready queue`() = runTest {
        val fake = FakeWorkSource().apply {
            add(done("AMPR-9"))
            add(queued("AMPR-1"))
            add(queued("AMPR-2", WorkSourceLabels.GATE_AWAITING_VERDICT))
            add(queued("AMPR-3").apply { blockedBy += "AMPR-1" })
            add(queued("AMPR-4").apply { blockedBy += "AMPR-9" })
        }

        val scan = port(fake).readyCandidates("w1").getOrThrow()

        assertEquals(listOf("AMPR-1", "AMPR-4"), scan.tickets.map { it.identifier })
        assertTrue(scan.tickets.all { it.status == CanonWorkStatus.TODO })
        assertTrue(scan.deferred.isEmpty())
    }

    @Test
    fun `a ticket read carries its description, suggested branch and blockers`() = runTest {
        val fake = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-9"))
            add(
                queued("AMPR-1").apply {
                    description = "**Model/effort recommendation:** Sonnet, low — fixture."
                    branchName = "miley/ampr-1-fixture"
                    blockedBy += "AMPR-9"
                },
            )
        }

        val ticket = port(fake).ticket("AMPR-1").getOrThrow()

        assertEquals("**Model/effort recommendation:** Sonnet, low — fixture.", ticket.description)
        assertEquals("miley/ampr-1-fixture", ticket.branchName)
        assertEquals(listOf("AMPR-9"), ticket.blockers)
        assertEquals("uuid-AMPR-1", ticket.uuid)
        assertEquals("Todo", ticket.statusName)
    }

    @Test
    fun `the in-flight scan sees every claimed or reviewing ticket with its scope`() = runTest {
        val fake = FakeWorkSource().apply {
            add(queued("AMPR-1"))
            add(started("AMPR-2").apply { description = "scope A" })
            add(started("AMPR-3", status = "In Review").apply { description = "scope B" })
        }

        val scan = port(fake).inFlight().getOrThrow()

        assertEquals(setOf("AMPR-2", "AMPR-3"), scan.tickets.map { it.identifier }.toSet())
        assertEquals(setOf("scope A", "scope B"), scan.tickets.mapNotNull { it.description }.toSet())
        assertTrue(scan.incomplete.isEmpty())
    }

    @Test
    fun `a claim is the adapter's claim protocol and a release gives back only this instance's claim`() = runTest {
        val fake = FakeWorkSource().apply {
            add(queued("AMPR-1"))
            add(queued("AMPR-2"))
        }
        val switchboard = port(fake)

        assertEquals(DispatchClaim.Won, switchboard.claim("AMPR-1").getOrThrow())
        assertEquals("In Progress", fake.issue("AMPR-1").status)

        port(fake, SupervisorInstanceId("switchboard-other")).claim("AMPR-2").getOrThrow()
        val lost = assertIs<DispatchClaim.Lost>(switchboard.claim("AMPR-2").getOrThrow())
        assertEquals("switchboard-other", lost.winner)

        val released = assertIs<ClaimRelease.Released>(switchboard.release("AMPR-1").getOrThrow())
        assertEquals("Todo", released.revertedTo)
        assertEquals("Todo", fake.issue("AMPR-1").status)
    }

    @Test
    fun `an escalation and a verdict request stop the ticket without moving it anywhere closed`() = runTest {
        val fake = FakeWorkSource().apply {
            add(started("AMPR-1"))
            add(started("AMPR-2", "In Progress", WorkSourceLabels.RESEARCH))
        }
        val switchboard = port(fake)

        switchboard.escalate("AMPR-1", "The agent exited with status 3.").getOrThrow()
        assertEquals("In Progress", fake.issue("AMPR-1").status, "a ticket escalates from where it stopped")
        assertTrue(WorkSourceLabels.GATE_ESCALATED in fake.issue("AMPR-1").labels)
        val escalation = assertIs<SupervisoryComment.Escalation>(
            SupervisoryComment.parse(fake.comments("AMPR-1").last().body),
        )
        assertEquals(instance, escalation.instanceId)

        switchboard.requestVerdict("AMPR-2").getOrThrow()
        assertEquals("In Review", fake.issue("AMPR-2").status)
        assertTrue(WorkSourceLabels.GATE_AWAITING_VERDICT in fake.issue("AMPR-2").labels)
    }

    @Test
    fun `surfacing moves a verified ticket to review with its merge request linked and touches no label`() = runTest {
        val fake = FakeWorkSource().apply {
            add(started("AMPR-1", "In Progress", "wave:w1", WorkSourceLabels.GATE_AWAITING_VERDICT))
        }

        port(fake).surfaceForReview(
            "AMPR-1",
            "https://github.com/socket-link/ampere/pull/12",
            "All 19 verification gates passed on the supervisor host.",
        ).getOrThrow()

        assertEquals(WorkSourceStates.IN_REVIEW, fake.issue("AMPR-1").status)
        assertTrue(
            WorkSourceLabels.GATE_AWAITING_VERDICT in fake.issue("AMPR-1").labels,
            "a gate a person applied mid-run must stand",
        )
        val comment = fake.comments("AMPR-1").last().body
        val verified = assertIs<SupervisoryComment.Verified>(SupervisoryComment.parse(comment))
        assertEquals(instance, verified.instanceId)
        assertTrue(verified.body.startsWith("https://github.com/socket-link/ampere/pull/12"), verified.body)
        fake.callsTo(WorkSourceToolPins.SAVE_ISSUE).forEach { call ->
            assertTrue((call.arguments["removeLabels"] as? JsonArray).isNullOrEmpty(), "surfacing removed a label")
        }
    }

    @Test
    fun `no operation removes a label or closes a ticket or posts a free-form comment`() = runTest {
        val fake = FakeWorkSource().apply {
            add(done("AMPR-9", WorkSourceLabels.RESEARCH))
            add(queued("AMPR-1").apply { blockedBy += "AMPR-9" })
            add(queued("AMPR-2", WorkSourceLabels.RESEARCH))
            add(
                started(
                    "AMPR-3",
                    "In Review",
                    "wave:w1",
                    WorkSourceLabels.GATE_AWAITING_VERDICT,
                    WorkSourceLabels.GATE_ESCALATED,
                ),
            )
        }
        val switchboard = port(fake)

        // Every operation the port offers, over a research ticket, a gated ticket and a
        // ticket blocked by a research ticket.
        switchboard.readyCandidates("w1").getOrThrow()
        switchboard.inFlight().getOrThrow()
        listOf("AMPR-1", "AMPR-2", "AMPR-3", "AMPR-9").forEach {
            switchboard.ticket(it).getOrThrow()
            switchboard.comments(it).getOrThrow()
        }
        switchboard.claim("AMPR-1").getOrThrow()
        switchboard.claim("AMPR-2").getOrThrow()
        switchboard.requestVerdict("AMPR-2").getOrThrow()
        switchboard.escalate("AMPR-2", "Research findings are ready for a human verdict.").getOrThrow()
        switchboard.escalate("AMPR-3", "Still stopped.").getOrThrow()
        switchboard.surfaceForReview("AMPR-3", "https://example.invalid/pull/3", "All gates passed.").getOrThrow()
        switchboard.release("AMPR-1").getOrThrow()

        val issueWrites = fake.callsTo(WorkSourceToolPins.SAVE_ISSUE)
        issueWrites.forEach { call ->
            val removed = call.arguments["removeLabels"] as? JsonArray
            assertTrue(removed.isNullOrEmpty(), "the Switchboard removed labels: ${call.arguments}")
        }
        val states = fake.transitions().toSet()
        assertTrue(
            states.none { it in setOf(WorkSourceStates.DONE, WorkSourceStates.CANCELED) },
            "states written: $states",
        )

        assertTrue(WorkSourceLabels.GATE_AWAITING_VERDICT in fake.issue("AMPR-3").labels)
        assertTrue(WorkSourceLabels.GATE_ESCALATED in fake.issue("AMPR-3").labels)

        fake.callsTo(WorkSourceToolPins.SAVE_COMMENT).forEach { call ->
            val body = assertNotNull((call.arguments["body"] as? JsonPrimitive)?.contentOrNull)
            assertNotNull(SupervisoryComment.parse(body), "every comment opens with a supervisory header: $body")
        }
    }
}
