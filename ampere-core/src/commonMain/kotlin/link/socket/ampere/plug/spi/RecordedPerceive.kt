package link.socket.ampere.plug.spi

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.CanonPerceivedEvent
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.CanonId
import link.socket.ampere.field.Belief
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject
import link.socket.ampere.field.observe.toObservation

/**
 * One page of a recorded perceive: the entities, and the [CanonPerceivedEvent] the door stored
 * for them.
 *
 * @property page The page as the source returned it. Consume it through [applyResidual] when it
 *   is not exact, exactly as an unrecorded page.
 * @property perceiveEventId The id every entity on [page] carries as
 *   `CanonProvenance.perceiveEventId`.
 * @property recordedAt The envelope's `recorded_at` for that event — what an Observation's
 *   `Provenance.createdAt` binds to.
 */
data class RecordedPerception<out E : CanonEntity>(
    val page: PerceivePage<E>,
    val perceiveEventId: EventId,
    val recordedAt: Instant,
) {

    /**
     * Every entity on [page] as an Observation held by [holder] — the perceiving Subject. Cannot
     * fail: [recordedPerceive] refuses a page with an unstamped entity, so every entity here has
     * the provenance [toObservation] needs.
     */
    fun observations(holder: Ref<Subject>): List<Belief.Settled> =
        page.entities.map { it.toObservation(holder, recordedAt).getOrThrow() }
}

/**
 * Perceive through the one door (F7): stamp the page with a perceive event's id, then publish
 * that event, then hand the page back.
 *
 * The order is what makes every entity's `perceiveEventId` resolvable:
 *
 * 1. Mint the event id and run the enumeration under it, as [PerceiveQuery.perceiveEventId]. The
 *    source stamps it at projection, so children built with `forChild` carry it too.
 * 2. Check that it did. An entity with no stamp, or another one, is [UnstampedPerceiveException]
 *    and nothing is published: a page the event cannot account for is not recorded as if it
 *    could be.
 * 3. Publish a [CanonPerceivedEvent] naming every entity. A door failure is returned and the page
 *    is dropped — an entity whose perceive event was never stored points at nothing.
 *
 * Only after all three does the page reach the caller. A source that cannot stamp — one that
 * projects without passing the query's id to `ReadableCanonAdapter.project` — fails at step 2
 * every time; call [PerceiveSource.perceive] directly for an unrecorded read.
 *
 * @param eventApi The door. Its agent is the event's source, and so the perceiving Subject.
 * @param runId The Arc run this perceive belongs to, when it belongs to one (F4).
 * @param causedBy The event whose handling triggered this perceive, if any (F2).
 */
suspend fun <E : CanonEntity> PerceiveSource<E>.recordedPerceive(
    query: PerceiveQuery,
    eventApi: AgentEventApi,
    runId: RunId? = null,
    causedBy: EventId? = null,
): Result<RecordedPerception<E>> {
    val perceiveEventId = generateUUID("canon-perceived", eventApi.agentId)
    val page = perceive(query.copy(perceiveEventId = perceiveEventId))
        .getOrElse { return Result.failure(it) }

    val unstamped = page.entities.filter { it.provenance.perceiveEventId != perceiveEventId }
    if (unstamped.isNotEmpty()) {
        return Result.failure(UnstampedPerceiveException(perceiveEventId, unstamped.map { it.canonId }))
    }

    val event = CanonPerceivedEvent(
        eventId = perceiveEventId,
        timestamp = eventApi.clock.now(),
        eventSource = EventSource.Agent(eventApi.agentId),
        linkId = query.linkId,
        canonIds = page.entities.map { it.canonId.value },
    )
    val stored = eventApi.publish(event, causedBy = causedBy, runId = runId)
        .getOrElse { return Result.failure(it) }

    return Result.success(RecordedPerception(page, perceiveEventId, stored.recordedAt))
}

/**
 * A [PerceiveSource] returned entities that do not carry the perceive event's id — it projected
 * without passing [PerceiveQuery.perceiveEventId] through, or stamped another one.
 */
class UnstampedPerceiveException(
    val perceiveEventId: EventId,
    val canonIds: List<CanonId>,
) : IllegalStateException(
    "Perceive $perceiveEventId returned ${canonIds.size} entit${if (canonIds.size == 1) "y" else "ies"} " +
        "not stamped with it: ${canonIds.joinToString { it.value }}",
)
