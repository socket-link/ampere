package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.link.LinkId

/**
 * Claim-by-write-then-verify: the protocol that works around a write surface with
 * no compare-and-swap.
 *
 * Two properties are load-bearing, and everything here tests one of them. **Only
 * one instance may believe it won** — two that both proceed is the failure the
 * protocol exists to prevent, and the earliest server timestamp is the only fact
 * both can compute identically. And **a loser must not overwrite a human** —
 * ratified rule B4 of the AMPR-291 verdict: read the state history first, and
 * defer rather than revert when anything else moved the ticket.
 */
class ClaimProtocolTest {

    private val linkId = LinkId("work-source-link")
    private val alpha = SupervisorInstanceId("supervisor-alpha")
    private val beta = SupervisorInstanceId("supervisor-beta")

    private fun seeded(): FakeWorkSource = FakeWorkSource().apply {
        add(FakeWorkSource.Issue("AMPR-101", labels = mutableListOf("wave:w0")))
    }

    private fun supervisor(tools: WorkSourceToolCaller, instanceId: SupervisorInstanceId) =
        LinearWorkSource(tools = tools, linkId = linkId, instanceId = instanceId)

    @Test
    fun `an uncontested claim wins and leaves the ticket in the claimed state`() = runTest {
        val fake = seeded()

        val outcome = supervisor(fake, alpha).claim("AMPR-101").getOrThrow()

        val won = assertIs<ClaimOutcome.Won>(outcome)
        assertEquals("AMPR-101", won.issue)
        assertEquals(alpha, won.claim.instanceId)
        assertEquals("In Progress", won.state)
        assertTrue(won.isWon)
        assertEquals("In Progress", fake.issue("AMPR-101").status)
    }

    @Test
    fun `the claim comment is posted before the transition`() = runTest {
        // Order is the protocol. A transition-first claim would let a slower
        // claimant with an earlier comment still win, after a faster one had
        // already started work.
        val fake = seeded()

        supervisor(fake, alpha).claim("AMPR-101").getOrThrow()

        val writes = fake.calls
            .map { it.tool }
            .filter { it == WorkSourceToolPins.SAVE_COMMENT || it == WorkSourceToolPins.SAVE_ISSUE }

        assertEquals(
            listOf(WorkSourceToolPins.SAVE_COMMENT, WorkSourceToolPins.SAVE_ISSUE),
            writes,
        )
        assertEquals(
            "claim:AMPR-101:supervisor-alpha",
            fake.comments("AMPR-101").single().body,
        )
    }

    @Test
    fun `the earliest claim comment wins and the loser reverts`() = runTest {
        val fake = seeded()
        // Alpha's process got its claim comment in a moment earlier, before
        // either instance had transitioned anything — the real shape of the race.
        val alphaClaim = fake.addComment(
            issue = "AMPR-101",
            body = SupervisoryComment.Claim("AMPR-101", alpha).render(),
        )

        val outcome = supervisor(fake, beta).claim("AMPR-101").getOrThrow()

        val lost = assertIs<ClaimOutcome.Lost>(outcome)
        val winner = assertNotNull(lost.winner)
        assertEquals(alpha, winner.instanceId)
        assertEquals(alphaClaim.id, winner.commentId)
        assertEquals("Todo", lost.revertedTo)
        assertEquals(
            "Todo",
            fake.issue("AMPR-101").status,
            "the loser put the ticket back exactly where it found it",
        )
        assertEquals(
            listOf("In Progress", "Todo"),
            fake.transitions(),
            "one transition in, one back out",
        )
    }

    @Test
    fun `a loser leaves the winner's claim comment alone`() = runTest {
        val fake = seeded()
        fake.addComment("AMPR-101", SupervisoryComment.Claim("AMPR-101", alpha).render())

        supervisor(fake, beta).claim("AMPR-101").getOrThrow()

        // Comments are append-only; abandoning a claim is not retracting it, and
        // nothing in this protocol deletes anything.
        assertEquals(
            listOf(
                "claim:AMPR-101:supervisor-alpha",
                "claim:AMPR-101:supervisor-beta",
            ),
            fake.comments("AMPR-101").map { it.body },
        )
    }

    @Test
    fun `a second claimant loses when the first already holds the ticket`() = runTest {
        val fake = seeded()
        val first = supervisor(fake, alpha).claim("AMPR-101").getOrThrow()
        val second = supervisor(fake, beta).claim("AMPR-101").getOrThrow()

        assertTrue(first.isWon)
        val lost = assertIs<ClaimOutcome.Lost>(second)
        assertEquals(alpha, lost.winner?.instanceId)
        assertEquals(
            "In Progress",
            fake.issue("AMPR-101").status,
            "beta found it already claimed, so putting it back means leaving it claimed",
        )
    }

    @Test
    fun `a claim comment for another issue never decides this race`() = runTest {
        val fake = seeded()
        // A claim comment naming a different issue, on this issue's thread. The
        // GitHub mirror and human cross-posting both make this possible.
        fake.addComment("AMPR-101", SupervisoryComment.Claim("AMPR-999", beta).render())
        fake.addComment("AMPR-101", "Looks good to me")

        val outcome = supervisor(fake, alpha).claim("AMPR-101").getOrThrow()

        assertEquals(alpha, assertIs<ClaimOutcome.Won>(outcome).claim.instanceId)
    }

    @Test
    fun `a loser defers instead of reverting when the ticket moved away`() = runTest {
        val fake = seeded()
        fake.addComment("AMPR-101", SupervisoryComment.Claim("AMPR-101", alpha).render())
        // A human reaches for the ticket in the window between beta's transition
        // and its arbitration read.
        fake.interfere = listOf("Done")

        val outcome = supervisor(fake, beta).claim("AMPR-101").getOrThrow()

        val deferred = assertIs<ClaimOutcome.Deferred>(outcome)
        val moved = assertIs<ClaimInterference.MovedAway>(deferred.interference)
        assertEquals("In Progress", moved.expected)
        assertEquals("Done", moved.observed)
        assertEquals("In Progress", deferred.claimedState)
        assertEquals(alpha, deferred.winner?.instanceId)
        assertEquals(
            "Done",
            fake.issue("AMPR-101").status,
            "the human's move stands; a revert here would destroy the record of it",
        )
        assertEquals(
            listOf("In Progress"),
            fake.transitions(),
            "beta wrote one transition and no revert; the move to Done was not its doing",
        )
    }

    @Test
    fun `a loser defers when extra transitions landed even if the state looks right`() = runTest {
        val fake = seeded()
        fake.addComment("AMPR-101", SupervisoryComment.Claim("AMPR-101", alpha).render())
        // Someone walked the ticket out and back again. The state matches, and
        // only the span count betrays the visit — which is why the check is on
        // history and not on the current state alone.
        fake.interfere = listOf("In Review", "In Progress")

        val outcome = supervisor(fake, beta).claim("AMPR-101").getOrThrow()

        val deferred = assertIs<ClaimOutcome.Deferred>(outcome)
        val extra = assertIs<ClaimInterference.ExtraTransitions>(deferred.interference)
        assertTrue(extra.spansAfter > extra.spansBefore + 1, "$extra")
        assertEquals(
            listOf("Todo", "In Progress", "In Review", "In Progress"),
            fake.issue("AMPR-101").history.map { it.name },
            "the ticket really did leave and come back",
        )
        assertEquals(listOf("In Progress"), fake.transitions(), "and beta wrote no revert")
    }

    @Test
    fun `a loser defers when the work source stopped reporting state history`() = runTest {
        // With no history there is no evidence either way, and a revert on no
        // evidence is a blind write.
        val fake = seeded()
        fake.addComment("AMPR-101", SupervisoryComment.Claim("AMPR-101", alpha).render())
        fake.omitStateHistory = true

        val outcome = supervisor(fake, beta).claim("AMPR-101").getOrThrow()

        val deferred = assertIs<ClaimOutcome.Deferred>(outcome)
        assertEquals(ClaimInterference.HistoryUnavailable, deferred.interference)
        assertEquals(listOf("In Progress"), fake.transitions())
    }

    @Test
    fun `the state history is read before any revert is written`() = runTest {
        val fake = seeded()
        fake.addComment("AMPR-101", SupervisoryComment.Claim("AMPR-101", alpha).render())

        supervisor(fake, beta).claim("AMPR-101").getOrThrow()

        // Ratified rule B4: the read that carries stateHistory has to come
        // between the losing transition and the revert, or the revert is blind.
        val tools = fake.calls.map { it.tool }
        val lastRead = tools.lastIndexOf(WorkSourceToolPins.GET_ISSUE)
        val revert = tools.lastIndexOf(WorkSourceToolPins.SAVE_ISSUE)
        assertTrue(lastRead < revert, "expected a read before the revert, got $tools")
        assertTrue(
            fake.callsTo(WorkSourceToolPins.GET_ISSUE).all { it.arguments.text("includeRelations") == "true" },
            "every issue read asks for relations, so the history and blockers both arrive",
        )
    }

    @Test
    fun `a claim that cannot be read back is a loss and not a win`() = runTest {
        // The claim comment was written and the arbitration read did not return
        // it. A claim this process cannot see in the total order is one it cannot
        // show it holds.
        val fake = SilentComments()

        val outcome = supervisor(fake, alpha).claim("AMPR-101").getOrThrow()

        val lost = assertIs<ClaimOutcome.Lost>(outcome)
        assertNull(lost.winner)
        assertEquals("Todo", lost.revertedTo)
    }

    @Test
    fun `claims are ordered by server timestamp and then by comment id`() {
        val at = Instant.parse("2026-09-26T12:00:00.500Z")
        val later = Instant.parse("2026-09-26T12:00:00.501Z")
        val records = listOf(
            ClaimRecord(SupervisoryComment.Claim("AMPR-1", beta), "comment-b", at),
            ClaimRecord(SupervisoryComment.Claim("AMPR-1", alpha), "comment-a", at),
            ClaimRecord(SupervisoryComment.Claim("AMPR-1", SupervisorInstanceId("gamma")), "comment-0", later),
        )

        val winner = records.minWithOrNull(CLAIM_ORDER)

        // Millisecond resolution means a tie is possible, and both racers must
        // compute the same winner from it or they both proceed.
        assertEquals("comment-a", assertNotNull(winner).commentId)
    }

    /** A work source whose comment thread never returns what was written to it. */
    private class SilentComments : WorkSourceToolCaller {
        private val delegate = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-101", labels = mutableListOf("wave:w0")))
        }

        override suspend fun listTools(): Result<List<McpToolDescriptor>> = delegate.listTools()

        override suspend fun call(
            tool: String,
            arguments: JsonObject,
        ): Result<ToolCallResult> = if (tool == WorkSourceToolPins.LIST_COMMENTS) {
            Result.success(textResult("""{"comments":[],"hasNextPage":false}"""))
        } else {
            delegate.call(tool, arguments)
        }
    }
}
