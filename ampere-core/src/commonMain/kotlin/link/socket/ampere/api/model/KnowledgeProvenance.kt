package link.socket.ampere.api.model

import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.events.StoredEvent

/**
 * The recorded origin of one knowledge entry: the entry, the cognitive element it was
 * distilled from, and the chain of events that produced it.
 *
 * Provenance is a walk over events, never over knowledge rows (F11, AMPR-417). A row's
 * `created_by_event` names the `KnowledgeStored` event that recorded it; that event's
 * envelope names, in `caused_by`, the event the write was reacting to; and so on back to an
 * event with no cause. [events] is that walk, producing event first.
 *
 * [sourceType] and [sourceId] are a separate, single fact: the one element the row was
 * distilled from — `idea_id`, `outcome_id`, `perception_id`, `plan_id` or `task_id`, picked by
 * the row's own [KnowledgeType]. Those elements have no rows of their own, so a source id is
 * not something to follow, and it is never a knowledge id (AMPR-350).
 *
 * @property entry The knowledge entry that was traced
 * @property sourceType Which kind of element the entry came from. Mirrors
 * [KnowledgeEntry.knowledgeType], and tells you which of the entry's five
 * source-id fields [sourceId] was read from
 * @property sourceId Id of that element, or null for a row that recorded no
 * source id. It addresses an `Idea`/`Outcome`/`Perception`/`Plan`/`Task` — it
 * is never the id of another knowledge entry, so passing it back to
 * [link.socket.ampere.api.service.KnowledgeService.get] will not find anything
 * @property events The producing event and each `caused_by` ancestor, in that order, each with
 * the envelope the store recorded. Empty for a row older than the `created_by_event` column
 * (AMPR-417), which names no event to start from
 * @property unresolvedEventId The id the walk was told to follow next and found no row for —
 * an event the budget has since pruned, or one the door refused after the row was written.
 * Null when the walk reached an event with no cause, which is a complete chain
 */
@link.socket.ampere.api.AmpereStableApi
data class KnowledgeProvenance(
    val entry: KnowledgeEntry,
    val sourceType: KnowledgeType,
    val sourceId: String?,
    val events: List<StoredEvent> = emptyList(),
    val unresolvedEventId: EventId? = null,
)
