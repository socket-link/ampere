package link.socket.ampere.api.model

import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType

/**
 * The recorded origin of one knowledge entry: the entry itself, plus the single
 * cognitive element it was distilled from.
 *
 * This is one hop, not a trail, because one hop is all `KnowledgeStore` records.
 * Every row carries exactly one source id — `idea_id`, `outcome_id`,
 * `perception_id`, `plan_id` or `task_id`, picked by the row's own
 * [KnowledgeType] — and no reference to a parent entry. The elements those ids
 * address (`Idea`, `Outcome`, `Perception`, `Plan`, `Task`) have no rows of
 * their own, so there is nothing further to resolve or follow. See AMPR-350.
 *
 * To relate several entries, use the Arc run that produced them: knowledge rows
 * carry `run_id`, and `ArcTraceProjection` rebuilds a run's phase-by-phase
 * narrative from it.
 *
 * @property entry The knowledge entry that was traced
 * @property sourceType Which kind of element the entry came from. Mirrors
 * [KnowledgeEntry.knowledgeType], and tells you which of the entry's five
 * source-id fields [sourceId] was read from
 * @property sourceId Id of that element, or null for a row that recorded no
 * source id. It addresses an `Idea`/`Outcome`/`Perception`/`Plan`/`Task` — it
 * is never the id of another knowledge entry, so passing it back to
 * [link.socket.ampere.api.service.KnowledgeService.get] will not find anything
 */
@link.socket.ampere.api.AmpereStableApi
data class KnowledgeProvenance(
    val entry: KnowledgeEntry,
    val sourceType: KnowledgeType,
    val sourceId: String?,
)
