package link.socket.ampere.agents.execution.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.GATE_AWAITING_VERDICT
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.REPOSITORY
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.RESEARCH
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.described
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.ticket
import link.socket.ampere.canon.CanonWorkStatus

/**
 * The ready rule (AMPR-308, Context §1; task 1's validation): blocked, gated,
 * scope-overlapping and verdict-less-research-blocker candidates are each refused,
 * each for its own reason.
 */
class ReadyQueueEvaluatorTest {

    private val source = FakeDispatchWorkSource()
    private val evaluator = ReadyQueueEvaluator(source, wave = "w1", repository = REPOSITORY)

    private fun evaluate(id: String, inFlight: List<InFlightTicket> = emptyList()): Readiness = runBlocking {
        evaluator.evaluate(source.current(id), inFlight)
    }

    private fun refusal(id: String, inFlight: List<InFlightTicket> = emptyList()): Refusal =
        assertIs<Readiness.Refused>(evaluate(id, inFlight), "$id should have been refused").refusal

    private fun inFlight(id: String, vararg paths: String): InFlightTicket =
        InFlightTicket(id, TicketScopes.parse(described(*paths)))

    @Test
    fun `the four refusals the ticket names each carry a distinct reason`() {
        source.add(ticket("AMPR-2", status = CanonWorkStatus.IN_PROGRESS))
        source.add(ticket("AMPR-10", blockers = listOf("AMPR-2")))

        source.add(ticket("AMPR-11", labels = setOf("wave:w1", GATE_AWAITING_VERDICT)))

        source.add(ticket("AMPR-12", description = described("docs/shared.md")))
        val overlapping = listOf(inFlight("AMPR-3", "docs/**"))

        source.add(ticket("AMPR-4", labels = setOf("wave:w0", RESEARCH), status = CanonWorkStatus.DONE))
        source.addComment("AMPR-4", "# Recon findings — STOP, requesting human verdict")
        source.add(ticket("AMPR-13", blockers = listOf("AMPR-4")))

        val reasons = listOf(
            refusal("AMPR-10"),
            refusal("AMPR-11"),
            refusal("AMPR-12", overlapping),
            refusal("AMPR-13"),
        ).map { it.reason }

        assertEquals(
            listOf(
                RefusalReason.BLOCKED,
                RefusalReason.GATED,
                RefusalReason.SCOPE_OVERLAP,
                RefusalReason.RESEARCH_BLOCKER_UNVERDICTED,
            ),
            reasons,
        )
        assertEquals(reasons.size, reasons.toSet().size, "each refusal must be distinguishable in the log")
    }

    @Test
    fun `a research blocker that is Done with a recorded verdict is satisfied`() {
        source.add(ticket("AMPR-4", labels = setOf(RESEARCH), status = CanonWorkStatus.DONE))
        source.addComment("AMPR-4", "# Recon findings")
        source.addComment("AMPR-4", "**Human verdict — recorded 2026-08-23.** Accepted.")
        source.add(ticket("AMPR-20", blockers = listOf("AMPR-4")))

        assertIs<Readiness.Ready>(evaluate("AMPR-20"))
    }

    @Test
    fun `a research blocker closed any other way than Done is not satisfied, verdict or not`() {
        source.add(ticket("AMPR-4", labels = setOf(RESEARCH), status = CanonWorkStatus.CANCELLED))
        source.addComment("AMPR-4", "**Human verdict — recorded 2026-08-23.** Superseded.")
        source.add(ticket("AMPR-21", blockers = listOf("AMPR-4")))

        assertEquals(RefusalReason.RESEARCH_BLOCKER_UNVERDICTED, refusal("AMPR-21").reason)
    }

    @Test
    fun `an ordinary blocker needs only to be closed`() {
        source.add(ticket("AMPR-5", status = CanonWorkStatus.DONE))
        source.add(ticket("AMPR-6", status = CanonWorkStatus.CANCELLED))
        source.add(ticket("AMPR-22", blockers = listOf("AMPR-5", "AMPR-6")))

        assertIs<Readiness.Ready>(evaluate("AMPR-22"))
    }

    @Test
    fun `a ticket with no scope block runs only alone`() {
        source.add(ticket("AMPR-23", description = "**Model/effort recommendation:** Sonnet, low — legacy ticket."))

        assertIs<Readiness.Ready>(evaluate("AMPR-23"), "unknown scope with nothing in flight may run")
        val refused = refusal("AMPR-23", listOf(inFlight("AMPR-3", "ampere-cli/**")))
        assertEquals(RefusalReason.SCOPE_UNKNOWN, refused.reason)
        assertTrue("AMPR-3" in refused.detail)
    }

    @Test
    fun `an in-flight ticket with no scope block blocks everything`() {
        source.add(ticket("AMPR-24"))
        val legacy = InFlightTicket("AMPR-3", TicketScopes.parse("no block here"))

        val refused = refusal("AMPR-24", listOf(legacy))
        assertEquals(RefusalReason.SCOPE_OVERLAP, refused.reason)
        assertTrue("AMPR-3" in refused.detail)
    }

    @Test
    fun `disjoint scopes run together`() {
        source.add(ticket("AMPR-25", description = described("ampere-cli/src/**")))
        assertIs<Readiness.Ready>(evaluate("AMPR-25", listOf(inFlight("AMPR-3", "ampere-core/src/**"))))
    }

    @Test
    fun `a scope that names no path in this repository is refused`() {
        source.add(ticket("AMPR-26", description = described("src/**", repo = "socket-link/phosphor")))
        assertEquals(RefusalReason.SCOPE_OUTSIDE_REPOSITORY, refusal("AMPR-26").reason)
    }

    @Test
    fun `a ticket that left the wave or the queue since the scan is refused`() {
        source.add(ticket("AMPR-27", labels = setOf("wave:w2")))
        source.add(ticket("AMPR-28", status = CanonWorkStatus.CLAIMED))

        assertEquals(RefusalReason.NOT_IN_WAVE, refusal("AMPR-27").reason)
        assertEquals(RefusalReason.NOT_QUEUED, refusal("AMPR-28").reason)
    }

    @Test
    fun `a read that fails refuses rather than passes`() {
        source.add(ticket("AMPR-7"))
        source.add(ticket("AMPR-29", blockers = listOf("AMPR-7")))
        source.add(ticket("AMPR-30", blockers = null))
        source.unreadable += "AMPR-7"

        assertEquals(RefusalReason.UNREADABLE, refusal("AMPR-29").reason)
        assertEquals(RefusalReason.UNREADABLE, refusal("AMPR-30").reason, "unread relations are not an empty list")
    }
}
