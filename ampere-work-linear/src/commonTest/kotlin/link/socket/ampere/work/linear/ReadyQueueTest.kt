package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.PerceivePredicate
import link.socket.ampere.plug.spi.PerceiveQuery
import link.socket.ampere.plug.spi.applyResidual

/**
 * The ready queue: an over-approximating server query, then client-side
 * filtering.
 *
 * The AMPR-289 probe ran the server-side query and got back both a blocked
 * ticket and a gated one. A caller reading the page's entities directly would
 * have dispatched both, and these tests hold the line on the two halves that
 * stop it: the source *admits* it did not filter, and the queue applies the rest.
 */
class ReadyQueueTest {

    private val instance = SupervisorInstanceId("supervisor-a")

    private fun seeded(): FakeWorkSource = FakeWorkSource().apply {
        // Ready: right wave, queued, no gate, no blockers.
        add(FakeWorkSource.Issue("AMPR-101", labels = mutableListOf("wave:w0")))
        // Blocked by an issue that is still open. Server-side query cannot see this.
        add(
            FakeWorkSource.Issue(
                identifier = "AMPR-102",
                labels = mutableListOf("wave:w0"),
                blockedBy = mutableListOf("AMPR-900"),
            ),
        )
        // Stopped at a human-verdict gate. Label negation is inexpressible server-side.
        add(
            FakeWorkSource.Issue(
                identifier = "AMPR-103",
                labels = mutableListOf("wave:w0", WorkSourceLabels.GATE_AWAITING_VERDICT),
            ),
        )
        // Escalated, which is a gate by another name.
        add(
            FakeWorkSource.Issue(
                identifier = "AMPR-104",
                labels = mutableListOf("wave:w0", WorkSourceLabels.GATE_ESCALATED),
            ),
        )
        // Blocked by an issue that is already done: not a blocker any more.
        add(
            FakeWorkSource.Issue(
                identifier = "AMPR-105",
                labels = mutableListOf("wave:w0"),
                blockedBy = mutableListOf("AMPR-901"),
            ),
        )
        // Another wave, and a ticket already in flight. Both are server-filtered.
        add(FakeWorkSource.Issue("AMPR-106", labels = mutableListOf("wave:w1")))
        add(
            FakeWorkSource.Issue(
                identifier = "AMPR-107",
                status = "In Progress",
                statusType = WorkItemStatusType.STARTED,
                labels = mutableListOf("wave:w0"),
            ),
        )
        // The blockers themselves.
        add(FakeWorkSource.Issue("AMPR-900", status = "Todo"))
        add(
            FakeWorkSource.Issue(
                identifier = "AMPR-901",
                status = "Done",
                statusType = WorkItemStatusType.COMPLETED,
            ),
        )
    }

    private fun workSource(fake: FakeWorkSource) = LinearWorkSource(
        tools = fake,
        linkId = LinkId("work-source-link"),
        instanceId = instance,
    )

    @Test
    fun `the server-side query over-approximates and says so`() = runTest {
        val fake = seeded()
        val query = PerceiveQuery(
            linkId = LinkId("work-source-link"),
            predicates = readyQueueRule(wave = "w0"),
        )

        val page = WorkSourceIssueSource(fake, LinkId("work-source-link")).perceive(query).getOrThrow()

        assertFalse(page.isExact, "the page cannot claim exactness it does not have")
        assertTrue(
            "AMPR-102" in page.entities.map { it.identifier },
            "the blocked ticket comes back from the server, exactly as the probe found",
        )
        assertTrue(
            "AMPR-103" in page.entities.map { it.identifier },
            "the gated ticket comes back from the server, exactly as the probe found",
        )
        assertEquals(
            listOf(WorkSourceFields.LABEL, WorkSourceFields.STATE),
            page.evaluated.map { (it as PerceivePredicate.Equals).field },
            "only a positive label and a state push down",
        )
        assertEquals(
            3,
            page.residual.size,
            "two gate negations and the blocker relation come back for the caller",
        )
    }

    @Test
    fun `the ready queue filters the blocked and the gated candidates client-side`() = runTest {
        val queue = workSource(seeded()).readyQueue(wave = "w0").getOrThrow()

        assertEquals(
            listOf("AMPR-101", "AMPR-105"),
            queue.items.map { it.identifier },
            "only the unblocked, ungated, queued tickets in this wave are ready",
        )
        assertTrue(queue.isExact, "nothing was left undecided, so this is the whole ready set")
        assertEquals(emptyList(), queue.deferred)
    }

    @Test
    fun `a blocker that is already done does not block`() = runTest {
        // The relation is still there; what matters is the blocker's state, which
        // costs one read per blocker because a relation does not carry it.
        val queue = workSource(seeded()).readyQueue(wave = "w0").getOrThrow()

        assertTrue("AMPR-105" in queue.items.map { it.identifier })
    }

    @Test
    fun `a candidate whose blockers cannot be read is deferred and not dispatched`() = runTest {
        val fake = seeded()
        fake.failGetIssue += "AMPR-900"

        val queue = workSource(fake).readyQueue(wave = "w0").getOrThrow()

        assertFalse(
            "AMPR-102" in queue.items.map { it.identifier },
            "an unverifiable candidate is never dispatched",
        )
        assertEquals(listOf("AMPR-102"), queue.deferred.map { it.issue })
        assertFalse(
            queue.isExact,
            "the queue may be incomplete, and a supervisor must be able to tell that from empty",
        )
    }

    @Test
    fun `the wave label and the queued state are pushed down as query arguments`() = runTest {
        val fake = seeded()

        workSource(fake).readyQueue(wave = "w0", project = "Act 7").getOrThrow()

        val call = fake.callsTo(WorkSourceToolPins.LIST_ISSUES).single()
        assertEquals("wave:w0", call.arguments.text("label"))
        assertEquals("Todo", call.arguments.text("state"))
        assertEquals("Act 7", call.arguments.text("project"))
    }

    @Test
    fun `only the first label equality pushes down`() = runTest {
        // The query surface has one `label` argument. Sending a second value
        // would overwrite the first, and the page would claim to have filtered on
        // a term it dropped.
        val fake = seeded()
        val source = WorkSourceIssueSource(fake, LinkId("work-source-link"))
        val query = PerceiveQuery(
            linkId = LinkId("work-source-link"),
            predicates = listOf(
                PerceivePredicate.Equals(WorkSourceFields.LABEL, "wave:w0"),
                PerceivePredicate.Equals(WorkSourceFields.LABEL, "ready"),
            ),
        )

        val page = source.perceive(query).getOrThrow()

        assertEquals(1, page.evaluated.size)
        assertEquals(
            listOf(PerceivePredicate.Equals(WorkSourceFields.LABEL, "ready")),
            page.residual,
        )
        assertEquals("wave:w0", fake.callsTo(WorkSourceToolPins.LIST_ISSUES).single().arguments.text("label"))
    }

    @Test
    fun `the queue scans by page and hands back a continuation cursor`() = runTest {
        val fake = seeded()
        val source = workSource(fake)
        val collected = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0

        do {
            val page = source.readyQueue(wave = "w0", limit = 2, cursor = cursor).getOrThrow()
            collected += page.items.map { it.identifier }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)

        // Five candidates match server-side, two at a time; the filtering happens
        // per page, so a page can legitimately come back empty and still continue.
        assertEquals(3, pages)
        assertEquals(listOf("AMPR-101", "AMPR-105"), collected)
    }

    @Test
    fun `an unknown predicate field defers rather than silently excluding`() = runTest {
        val fake = seeded()
        val source = WorkSourceIssueSource(fake, LinkId("work-source-link"))
        val evaluator = WorkSourceIssueEvaluator(fake)
        val query = PerceiveQuery(
            linkId = LinkId("work-source-link"),
            predicates = listOf(
                PerceivePredicate.Equals(WorkSourceFields.LABEL, "wave:w0"),
                PerceivePredicate.Equals("estimate", "3"),
            ),
        )

        val page = source.perceive(query).getOrThrow()
        val exact = page.applyResidual(evaluator)

        assertEquals(emptyList(), exact.entities, "nothing can satisfy a term nobody can evaluate")
        assertTrue(evaluator.deferred.isNotEmpty(), "and every exclusion is recorded")
        assertTrue(evaluator.deferred.all { "estimate" in it.reason }, "${evaluator.deferred}")
    }

    @Test
    fun `the ready-queue rule is the ratified rule`() {
        val rule = readyQueueRule(wave = "w0")

        assertEquals(
            listOf(
                PerceivePredicate.Equals("label", "wave:w0"),
                PerceivePredicate.Equals("state", "Todo"),
                PerceivePredicate.Not(PerceivePredicate.Equals("label", "gate:awaiting-verdict")),
                PerceivePredicate.Not(PerceivePredicate.Equals("label", "gate:escalated")),
                PerceivePredicate.HasNoRelation("blocked-by-open"),
            ),
            rule,
        )
    }
}
