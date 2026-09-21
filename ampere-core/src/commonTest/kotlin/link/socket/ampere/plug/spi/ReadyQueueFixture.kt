package link.socket.ampere.plug.spi

import link.socket.ampere.canon.CanonType

/**
 * A work-source shaped fixture: the AMPR-289 ready-queue, where the tracker
 * can filter on a positive label server-side but can evaluate neither label
 * negation nor relation-absence.
 */
data class FixtureTicket(
    val id: String,
    val labels: Set<String> = emptySet(),
    val openBlockers: Set<String> = emptySet(),
)

const val BLOCKED_BY_OPEN = "blocked-by-open"

val readyQueueRule: List<PerceivePredicate> = listOf(
    PerceivePredicate.Equals(field = "label", value = "wave:w0"),
    PerceivePredicate.Not(PerceivePredicate.Equals(field = "label", value = "gate:awaiting-verdict")),
    PerceivePredicate.HasNoRelation(kind = BLOCKED_BY_OPEN),
)

object TicketEvaluator : PredicateEvaluator<FixtureTicket> {
    override suspend fun matches(entity: FixtureTicket, predicate: PerceivePredicate.Equals): Boolean =
        predicate.field == "label" && predicate.value in entity.labels

    override suspend fun hasNoRelation(entity: FixtureTicket, predicate: PerceivePredicate.HasNoRelation): Boolean =
        predicate.kind == BLOCKED_BY_OPEN && entity.openBlockers.isEmpty()
}

/**
 * Pushes down `Equals("label", …)` only — everything else is returned as
 * residual, as the contract requires. Pages [pageSize] tickets at a time
 * with the offset as cursor.
 */
class ReadyQueueSource(
    private val tickets: List<FixtureTicket>,
    private val pageSize: Int = 2,
) : PerceiveSource<FixtureTicket> {

    override val emits: Set<CanonType> = emptySet()

    override suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<FixtureTicket>> {
        val (pushedDown, residual) = query.predicates.partition {
            it is PerceivePredicate.Equals && it.field == "label"
        }
        val matching = tickets.filter { ticket ->
            pushedDown.all { (it as PerceivePredicate.Equals).value in ticket.labels }
        }
        val offset = query.cursor?.toInt() ?: 0
        val end = minOf(offset + pageSize, matching.size)
        return Result.success(
            PerceivePage(
                entities = matching.subList(offset, end),
                evaluated = pushedDown,
                residual = residual,
                nextCursor = end.takeIf { it < matching.size }?.toString(),
            ),
        )
    }
}
