package link.socket.ampere.agents.execution.dispatch

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonWorkStatus

/**
 * An in-memory [DispatchWorkSource] that records every write the Switchboard makes.
 *
 * Its ready queue applies only what the real one pushes down server-side — the wave
 * label and the queued state — so a gated or blocked ticket still reaches the
 * Switchboard as a candidate, and the Switchboard's own re-check is what refuses it.
 * That is the defense in depth under test, not a shortcut.
 *
 * Thread-safe: supervision settles runs on IO threads. Nothing here suspends inside
 * the lock.
 */
internal class FakeDispatchWorkSource(
    override val conventions: DispatchConventions = CONVENTIONS,
) : DispatchWorkSource {

    /** One write, as the work source would have received it. */
    sealed interface Write {
        val ticketId: String

        data class Claim(override val ticketId: String) : Write
        data class Release(override val ticketId: String) : Write
        data class Escalate(override val ticketId: String, val reason: String) : Write
        data class RequestVerdict(override val ticketId: String) : Write
        data class Surface(override val ticketId: String, val mergeRequestUrl: String, val summary: String) : Write
    }

    private val lock = Any()
    private val tickets = linkedMapOf<String, DispatchTicket>()
    private val comments = mutableMapOf<String, MutableList<TicketComment>>()

    val writes: MutableList<Write> = CopyOnWriteArrayList()

    /** What the next claim of a ticket returns. Won by default. */
    @Volatile
    var claimOutcome: (String) -> DispatchClaim = { DispatchClaim.Won }

    /** Tickets whose reads fail. */
    val unreadable: MutableSet<String> = mutableSetOf()

    fun add(ticket: DispatchTicket): DispatchTicket = synchronized(lock) {
        tickets[ticket.identifier] = ticket
        ticket
    }

    fun addComment(ticketId: String, body: String): Unit = synchronized(lock) {
        val thread = comments.getOrPut(ticketId) { mutableListOf() }
        thread += TicketComment(
            id = "comment-${thread.size + 1}",
            body = body,
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000 + thread.size),
            author = "Operator",
        )
    }

    fun current(ticketId: String): DispatchTicket = synchronized(lock) { requireNotNull(tickets[ticketId]) }

    fun writesTo(ticketId: String): List<Write> = writes.filter { it.ticketId == ticketId }

    override suspend fun readyCandidates(wave: String): Result<CandidateScan> = synchronized(lock) {
        Result.success(
            CandidateScan(
                tickets = tickets.values.filter {
                    conventions.waveLabel(wave) in it.labels && it.status == CanonWorkStatus.TODO
                },
            ),
        )
    }

    override suspend fun inFlight(): Result<InFlightScan> = synchronized(lock) {
        Result.success(InFlightScan(tickets = tickets.values.filter { it.status in IN_FLIGHT }))
    }

    override suspend fun ticket(ticketId: String): Result<DispatchTicket> {
        synchronized(lock) {
            if (ticketId in unreadable) return Result.failure(IllegalStateException("$ticketId is unavailable"))
            return tickets[ticketId]?.let { Result.success(it) }
                ?: Result.failure(NoSuchElementException("no ticket $ticketId"))
        }
    }

    override suspend fun comments(ticketId: String): Result<List<TicketComment>> {
        synchronized(lock) {
            if (ticketId in unreadable) return Result.failure(IllegalStateException("$ticketId is unavailable"))
            return Result.success(comments[ticketId].orEmpty().toList())
        }
    }

    override suspend fun claim(ticketId: String): Result<DispatchClaim> {
        synchronized(lock) {
            writes += Write.Claim(ticketId)
            val outcome = claimOutcome(ticketId)
            if (outcome == DispatchClaim.Won) {
                tickets[ticketId] = current(ticketId).copy(status = CanonWorkStatus.CLAIMED, statusName = "In Progress")
            }
            return Result.success(outcome)
        }
    }

    override suspend fun release(ticketId: String): Result<ClaimRelease> {
        synchronized(lock) {
            writes += Write.Release(ticketId)
            tickets[ticketId] = current(ticketId).copy(status = CanonWorkStatus.TODO, statusName = "Todo")
            return Result.success(ClaimRelease.Released(commentPosted = true, revertedTo = "Todo"))
        }
    }

    override suspend fun escalate(ticketId: String, reason: String): Result<Unit> {
        synchronized(lock) {
            writes += Write.Escalate(ticketId, reason)
            tickets[ticketId] = current(ticketId).let { it.copy(labels = it.labels + GATE_ESCALATED) }
            return Result.success(Unit)
        }
    }

    override suspend fun requestVerdict(ticketId: String): Result<Unit> {
        synchronized(lock) {
            writes += Write.RequestVerdict(ticketId)
            tickets[ticketId] = current(ticketId).let {
                it.copy(
                    labels = it.labels + GATE_AWAITING_VERDICT,
                    status = CanonWorkStatus.VERDICT_REQUESTED,
                    statusName = "In Review",
                )
            }
            return Result.success(Unit)
        }
    }

    override suspend fun surfaceForReview(ticketId: String, mergeRequestUrl: String, summary: String): Result<Unit> {
        synchronized(lock) {
            writes += Write.Surface(ticketId, mergeRequestUrl, summary)
            tickets[ticketId] = current(ticketId).copy(status = CanonWorkStatus.VERIFYING, statusName = "In Review")
            return Result.success(Unit)
        }
    }

    companion object {

        const val GATE_AWAITING_VERDICT = "gate:awaiting-verdict"
        const val GATE_ESCALATED = "gate:escalated"
        const val RESEARCH = "research"
        const val REPOSITORY = "socket-link/ampere"

        val CONVENTIONS = DispatchConventions(
            wavePrefix = "wave:",
            gateLabels = setOf(GATE_AWAITING_VERDICT, GATE_ESCALATED),
            researchLabel = RESEARCH,
            attachmentPrefix = "linear",
        )

        private val IN_FLIGHT = setOf(
            CanonWorkStatus.CLAIMED,
            CanonWorkStatus.IN_PROGRESS,
            CanonWorkStatus.VERIFYING,
        )

        /** A ticket description with a recommendation line and a scope block over [paths]. */
        fun described(
            vararg paths: String,
            recommendation: String = "Sonnet, low — test fixture.",
            repo: String = REPOSITORY,
            exclusive: Boolean? = null,
        ): String = buildString {
            appendLine("**Model/effort recommendation:** $recommendation")
            appendLine()
            appendLine("## Context")
            appendLine()
            appendLine("```ampere-scope")
            appendLine("repos:")
            appendLine("  - repo: $repo")
            appendLine("    paths:")
            paths.forEach { appendLine("      - '$it'") }
            exclusive?.let { appendLine("exclusive: $it") }
            appendLine("```")
        }

        /** A queued ticket in wave `w1`, blocked by nothing, writing `docs/<id>.md`. */
        fun ticket(
            id: String,
            labels: Set<String> = setOf("wave:w1"),
            status: CanonWorkStatus = CanonWorkStatus.TODO,
            statusName: String = statusNameOf(status),
            description: String? = described("docs/${id.lowercase()}.md"),
            blockers: List<String>? = emptyList(),
        ): DispatchTicket = DispatchTicket(
            identifier = id,
            title = "Ticket $id",
            status = status,
            statusName = statusName,
            labels = labels,
            description = description,
            blockers = blockers,
            branchName = "miley/${id.lowercase()}-fixture",
            url = "https://example.invalid/issue/$id",
            uuid = "uuid-$id",
        )

        private fun statusNameOf(status: CanonWorkStatus): String = when (status) {
            CanonWorkStatus.TODO -> "Todo"
            CanonWorkStatus.DONE -> "Done"
            CanonWorkStatus.CANCELLED -> "Canceled"
            CanonWorkStatus.VERIFYING, CanonWorkStatus.VERDICT_REQUESTED -> "In Review"
            CanonWorkStatus.BACKLOG -> "Backlog"
            else -> "In Progress"
        }
    }
}
