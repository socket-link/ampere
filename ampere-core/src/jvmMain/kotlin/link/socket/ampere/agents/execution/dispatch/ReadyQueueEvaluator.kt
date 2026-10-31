package link.socket.ampere.agents.execution.dispatch

import link.socket.ampere.canon.CanonWorkStatus

/** A ticket the scope rule has to respect: dispatched, by anyone, and what it may write. */
data class InFlightTicket(
    val ticketId: String,
    val scope: TicketScope,
)

/** Why one candidate was not dispatched. [detail] names what the rule saw. */
data class Refusal(
    val ticketId: String,
    val reason: RefusalReason,
    val detail: String,
)

/** The ready-queue verdict on one candidate. */
sealed interface Readiness {

    /** Dispatchable. [ticket] is the fresh read the verdict was reached on. */
    data class Ready(val ticket: DispatchTicket, val scope: TicketScope) : Readiness

    data class Refused(val refusal: Refusal) : Readiness
}

/**
 * The Switchboard's ready rule (AMPR-308, Context §1), applied to one candidate.
 *
 * A ticket is ready when it carries the wave's label, carries no gate label, sits
 * in the queued state, has no open blocker, has no `research` blocker that is not
 * both Done and carrying a human verdict, and declares a scope that overlaps nothing
 * in flight. The first rule a candidate fails is the [RefusalReason] it is refused
 * for.
 *
 * ### Re-checked on a fresh read
 *
 * The work source's ready queue already applied the wave, state, gate and blocker
 * terms. They are applied again here, to a read taken immediately before the claim,
 * for two reasons: the research rule needs the blockers' own labels and comments
 * anyway, and a ticket gated between the queue scan and the claim must still not be
 * dispatched. A gated ticket reaching a claim is the failure the ratified boundaries
 * exist to make impossible, so it is checked at the last read before the write.
 *
 * ### Fails toward refusing
 *
 * Every read that fails is [RefusalReason.UNREADABLE], never a pass: "we could not
 * check the blocker" must not read as "there is no blocker".
 *
 * @param repository The repository the Switchboard dispatches into, as `owner/name`,
 *   when known. A declared scope that names no path in it is refused: an agent in this
 *   repository's worktree could not do that ticket's work.
 */
class ReadyQueueEvaluator(
    private val workSource: DispatchWorkSource,
    private val wave: String,
    private val repository: String? = null,
) {

    private val conventions: DispatchConventions get() = workSource.conventions

    /** The verdict on [candidate], given what is already [inFlight]. */
    suspend fun evaluate(candidate: DispatchTicket, inFlight: Collection<InFlightTicket>): Readiness {
        val id = candidate.identifier
        val fresh = workSource.ticket(id).getOrElse { error ->
            return refuse(id, RefusalReason.UNREADABLE, "could not read the ticket: ${error.describe()}")
        }

        val waveLabel = conventions.waveLabel(wave)
        if (waveLabel !in fresh.labels) {
            return refuse(id, RefusalReason.NOT_IN_WAVE, "no longer labeled '$waveLabel'")
        }
        val gates = fresh.labels.intersect(conventions.gateLabels)
        if (gates.isNotEmpty()) {
            return refuse(id, RefusalReason.GATED, "carries ${gates.sorted().joinToString()}")
        }
        if (fresh.status != CanonWorkStatus.TODO) {
            return refuse(id, RefusalReason.NOT_QUEUED, "in '${fresh.statusName}', not the queued state")
        }

        blockerRefusal(fresh)?.let { return Readiness.Refused(it) }

        val scope = TicketScopes.parse(fresh.description)
        scopeRefusal(fresh, scope, inFlight)?.let { return Readiness.Refused(it) }

        return Readiness.Ready(fresh, scope)
    }

    private suspend fun blockerRefusal(ticket: DispatchTicket): Refusal? {
        val id = ticket.identifier
        val blockerIds = ticket.blockers
            ?: return Refusal(id, RefusalReason.UNREADABLE, "the read carried no relations, so blockers are unknown")

        val blockers = blockerIds.map { blockerId ->
            workSource.ticket(blockerId).getOrElse { error ->
                return Refusal(id, RefusalReason.UNREADABLE, "could not read blocker $blockerId: ${error.describe()}")
            }
        }

        blockers.firstOrNull { it.status !in CLOSED }?.let { open ->
            return Refusal(id, RefusalReason.BLOCKED, "blocked by ${open.identifier} (${open.statusName})")
        }

        blockers.filter { conventions.researchLabel in it.labels }.forEach { research ->
            if (research.status != CanonWorkStatus.DONE) {
                return Refusal(
                    id,
                    RefusalReason.RESEARCH_BLOCKER_UNVERDICTED,
                    "research blocker ${research.identifier} closed as '${research.statusName}', not Done",
                )
            }
            val comments = workSource.comments(research.identifier).getOrElse { error ->
                return Refusal(
                    id,
                    RefusalReason.UNREADABLE,
                    "could not read the comments of research blocker ${research.identifier}: ${error.describe()}",
                )
            }
            if (!HumanVerdicts.anyVerdict(comments)) {
                return Refusal(
                    id,
                    RefusalReason.RESEARCH_BLOCKER_UNVERDICTED,
                    "research blocker ${research.identifier} is Done with no human verdict recorded",
                )
            }
        }
        return null
    }

    private fun scopeRefusal(
        ticket: DispatchTicket,
        scope: TicketScope,
        inFlight: Collection<InFlightTicket>,
    ): Refusal? {
        val id = ticket.identifier
        val others = inFlight.filter { it.ticketId != id }

        if (repository != null && scope is TicketScope.Declared && scope.entryFor(repository) == null) {
            return Refusal(
                id,
                RefusalReason.SCOPE_OUTSIDE_REPOSITORY,
                "its scope declares ${scope.repos.joinToString { it.repo }}, not $repository",
            )
        }

        if (scope is TicketScope.Unknown && others.isNotEmpty()) {
            return Refusal(
                id,
                RefusalReason.SCOPE_UNKNOWN,
                "${scope.reason}; unknown scope overlaps everything, and " +
                    "${others.joinToString { it.ticketId }} ${if (others.size == 1) "is" else "are"} in flight",
            )
        }

        others.forEach { other ->
            TicketScopes.overlap(scope, other.scope)?.let { why ->
                val subject = if (other.scope is TicketScope.Unknown) "in-flight ${other.ticketId} has $why" else why
                return Refusal(id, RefusalReason.SCOPE_OVERLAP, "overlaps ${other.ticketId}: $subject")
            }
        }
        return null
    }

    private fun refuse(id: String, reason: RefusalReason, detail: String): Readiness =
        Readiness.Refused(Refusal(id, reason, detail))

    private fun Throwable.describe(): String = message ?: this::class.simpleName.orEmpty()

    private companion object {
        /** A blocker in one of these no longer blocks — Done or Cancelled, as the adapter's blocker rule reads it. */
        val CLOSED = setOf(CanonWorkStatus.DONE, CanonWorkStatus.CANCELLED)
    }
}
