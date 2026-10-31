package link.socket.ampere.work.linear

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.agents.execution.dispatch.CandidateScan
import link.socket.ampere.agents.execution.dispatch.ClaimRelease
import link.socket.ampere.agents.execution.dispatch.DeferredTicket
import link.socket.ampere.agents.execution.dispatch.DispatchClaim
import link.socket.ampere.agents.execution.dispatch.DispatchConventions
import link.socket.ampere.agents.execution.dispatch.DispatchTicket
import link.socket.ampere.agents.execution.dispatch.DispatchWorkSource
import link.socket.ampere.agents.execution.dispatch.InFlightScan
import link.socket.ampere.agents.execution.dispatch.TicketComment
import link.socket.ampere.plug.spi.PerceivePredicate
import link.socket.ampere.plug.spi.PerceiveQuery

/**
 * Binds this work source to the Switchboard's [DispatchWorkSource] port (AMPR-308,
 * over AMPR-305's adapter).
 *
 * ### What this binding withholds
 *
 * It is the narrowing that makes the Switchboard's trust boundaries structural.
 * [LinearWorkSource] can remove labels, write any state and post any comment; this
 * class exposes none of that. Its writes are [LinearWorkSource.claim],
 * [LinearWorkSource.release] (for this instance's own claim), [LinearWorkSource.escalate],
 * [LinearWorkSource.requestVerdict] — which *adds* [WorkSourceLabels.GATE_AWAITING_VERDICT]
 * and, as a stop, removes no gate ([SupervisoryStatusMapping.gateEdit]) — and
 * [LinearWorkSource.surfaceForReview], a raw move to review that touches no label. So
 * no path from the Switchboard reaches a gate removal or a Done transition, and
 * `LinearDispatchWorkSourceTest` pins that on the wire.
 *
 * ### Reads
 *
 * Translation only. A ticket's canonical status comes from
 * [SupervisoryStatusMapping.canonStatusFor], its description and suggested branch name
 * from the verbatim issue object — the read model carries neither as a typed field, and
 * neither is a field the adapter itself depends on.
 *
 * @param project Narrows every scan to one project, when given.
 */
class LinearDispatchWorkSource(
    private val workSource: LinearWorkSource,
    private val project: String? = null,
) : DispatchWorkSource {

    override val conventions: DispatchConventions = DispatchConventions(
        wavePrefix = WorkSourceLabels.WAVE_PREFIX,
        gateLabels = WorkSourceLabels.GATES,
        researchLabel = WorkSourceLabels.RESEARCH,
        attachmentPrefix = ATTACHMENT_PREFIX,
    )

    private val claims = LinearClaimRegistry(workSource)

    override suspend fun readyCandidates(wave: String): Result<CandidateScan> {
        val tickets = mutableListOf<DispatchTicket>()
        val deferred = mutableListOf<DeferredTicket>()
        val incomplete = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0

        while (true) {
            val page = workSource.readyQueue(wave = wave, project = project, cursor = cursor)
                .getOrElse { return Result.failure(it) }
            tickets += page.items.map { it.toDispatchTicket() }
            deferred += page.deferred.map { DeferredTicket(it.issue, it.reason) }
            page.partialFailures.forEach { incomplete += "an issue could not be projected: $it" }

            cursor = page.nextCursor ?: break
            if (++pages >= MAX_PAGES) {
                incomplete += "the ready-queue scan stopped at $MAX_PAGES pages"
                break
            }
        }
        return Result.success(CandidateScan(tickets = tickets, deferred = deferred, incomplete = incomplete))
    }

    override suspend fun inFlight(): Result<InFlightScan> {
        val tickets = mutableListOf<DispatchTicket>()
        val incomplete = mutableListOf<String>()

        SupervisoryState.IN_FLIGHT.sorted().forEach { state ->
            var cursor: String? = null
            var pages = 0
            while (true) {
                val query = PerceiveQuery(
                    linkId = workSource.linkId,
                    cursor = cursor,
                    predicates = buildList {
                        add(PerceivePredicate.Equals(WorkSourceFields.STATE, state))
                        project?.let { add(PerceivePredicate.Equals(WorkSourceFields.PROJECT, it)) }
                    },
                )
                val page = workSource.source.perceive(query).getOrElse { return Result.failure(it) }
                page.partialFailures.forEach { incomplete += "an issue in '$state' could not be projected: $it" }

                // A listed issue carries no description, and the description is where
                // the scope is, so each one costs a read.
                page.entities.forEach { listed ->
                    workSource.readIssue(listed.identifier).fold(
                        onSuccess = { tickets += it.toDispatchTicket() },
                        onFailure = { error ->
                            incomplete += "could not read ${listed.identifier}: ${error.message ?: error}"
                        },
                    )
                }

                cursor = page.nextCursor ?: break
                if (++pages >= MAX_PAGES) {
                    incomplete += "the scan of '$state' stopped at $MAX_PAGES pages"
                    break
                }
            }
        }
        return Result.success(InFlightScan(tickets = tickets, incomplete = incomplete))
    }

    override suspend fun ticket(ticketId: String): Result<DispatchTicket> =
        workSource.readIssue(ticketId).map { it.toDispatchTicket() }

    override suspend fun comments(ticketId: String): Result<List<TicketComment>> =
        workSource.readComments(ticketId).map { comments ->
            comments.map { TicketComment(id = it.id, body = it.body, createdAt = it.createdAt, author = it.authorName) }
        }

    override suspend fun claim(ticketId: String): Result<DispatchClaim> =
        workSource.claim(ticketId).map { outcome ->
            when (outcome) {
                is ClaimOutcome.Won -> DispatchClaim.Won
                is ClaimOutcome.Lost -> DispatchClaim.Lost(winner = outcome.winner?.instanceId?.value)
                is ClaimOutcome.Deferred -> DispatchClaim.Deferred(
                    reason = "lost to ${outcome.winner?.instanceId?.value ?: "another claimant"}, and the ticket " +
                        "moved in the window, so the state was left alone: ${outcome.interference}",
                )
            }
        }

    override suspend fun release(ticketId: String): Result<ClaimRelease> =
        claims.releaseClaim(ticketId, heldBy = workSource.instanceId.value)

    override suspend fun escalate(ticketId: String, reason: String): Result<Unit> =
        workSource.escalate(ticketId, reason).map { }

    override suspend fun requestVerdict(ticketId: String): Result<Unit> =
        workSource.requestVerdict(ticketId).map { }

    override suspend fun surfaceForReview(ticketId: String, mergeRequestUrl: String, summary: String): Result<Unit> =
        workSource.surfaceForReview(ticketId, mergeRequestUrl, summary).map { }

    private fun WorkSourceIssue.toDispatchTicket(): DispatchTicket = DispatchTicket(
        identifier = identifier,
        title = title,
        status = SupervisoryStatusMapping.canonStatusFor(supervisoryExpression()),
        statusName = statusName,
        labels = labels.toSet(),
        description = nativeString(DESCRIPTION_FIELD),
        blockers = blockedBy?.map { it.identifier },
        branchName = nativeString(BRANCH_NAME_FIELD),
        url = url,
        uuid = uuid,
    )

    private fun WorkSourceIssue.nativeString(field: String): String? =
        (native[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {

        /** How the operator's tooling names this source in a context path: `linear-<uuid>`. */
        const val ATTACHMENT_PREFIX: String = "linear"

        /** The issue read's description field. */
        internal const val DESCRIPTION_FIELD: String = "description"

        /** The issue read's suggested branch name — the tracker's "copy branch name". */
        internal const val BRANCH_NAME_FIELD: String = "gitBranchName"

        /** Scans terminate; a work source that never stops paging is reported, not followed. */
        private const val MAX_PAGES = 50
    }
}
