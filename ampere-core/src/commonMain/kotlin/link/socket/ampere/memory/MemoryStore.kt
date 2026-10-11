package link.socket.ampere.memory

import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.api.AmpereStableApi

/**
 * Public injection seam for agent-scoped persistent memory.
 *
 * `MemoryStore` is the contract embedded consumers (e.g. the Socket client)
 * implement to own durable storage for agent learning. Ampere consumes the
 * store at construction time and uses its two component repositories as the
 * exclusive read/write surface for:
 *
 * - [knowledge]: semantic recall over accumulated learnings (Idea / Outcome /
 *   Perception / Plan / Task knowledge entries with tags, task types, and
 *   complexity metadata).
 * - [outcomes]: episodic memory of every execution attempt — successes and
 *   failures keyed by ticket and executor.
 *
 * ## Why composition, not a single widened interface
 *
 * The two repositories already exist as separate interface seams used
 * throughout Ampere. Wrapping them in a single widened type would force
 * every implementer to re-state the full surface and would force Ampere to
 * adapt its existing call sites. Composition keeps the existing internals
 * untouched: bundled implementations ([link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl]
 * and [link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl])
 * continue to back Ampere's default construction path; the only consumer of
 * `MemoryStore` directly is the public injection seam.
 *
 * ## Holders and viewers
 *
 * Scoping is not left to the implementation (F9, F12, AMPR-417 — this
 * withdraws the earlier "Ampere does not pass an `AgentId`" contract). Every
 * knowledge write names its holder, its sensitivity, its confidence and the
 * event that recorded it, and every knowledge read names the Subject it is
 * made for. An implementation stores those columns as given and applies the
 * read predicate — PUBLIC rows plus the viewer's own, PUBLIC only for a null
 * viewer — before any limit. See [KnowledgeRepository].
 *
 * A consumer may still construct one `MemoryStore` per user or installation
 * (Socket does, for per-user durable on-device memory); that partitions the
 * storage, and the holder/viewer predicate applies within each partition.
 *
 * ## Default implementation
 *
 * For callers that do not supply a custom store, Ampere provides a bundled
 * factory ([memoryStoreOf]) that wraps the existing database-backed
 * repositories. This preserves today's behavior: callers that don't care
 * about injection get the same SQLDelight-backed persistence they had
 * before.
 *
 * @see KnowledgeRepository
 * @see OutcomeMemoryRepository
 */
@AmpereStableApi
interface MemoryStore {

    /**
     * Repository for storing and recalling agent learnings (Knowledge).
     *
     * Consumers implementing this property should provide durable, queryable
     * storage that keeps every belief column a write passes and honours the
     * viewer every read passes.
     */
    val knowledge: KnowledgeRepository

    /**
     * Repository for recording and querying execution outcomes (episodic memory).
     *
     * Consumers implementing this property should provide durable, queryable
     * storage scoped to the owning agent / user.
     */
    val outcomes: OutcomeMemoryRepository
}

/**
 * Compose a [MemoryStore] from existing repository implementations.
 *
 * This is the bundled construction path Ampere uses by default. External
 * consumers implementing custom backends typically supply their own
 * [KnowledgeRepository] / [OutcomeMemoryRepository] implementations and
 * pass them here, or implement [MemoryStore] directly.
 *
 * @param knowledge The knowledge repository to expose via [MemoryStore.knowledge]
 * @param outcomes The outcome memory repository to expose via [MemoryStore.outcomes]
 */
@AmpereStableApi
fun memoryStoreOf(
    knowledge: KnowledgeRepository,
    outcomes: OutcomeMemoryRepository,
): MemoryStore = DefaultMemoryStore(knowledge, outcomes)

private data class DefaultMemoryStore(
    override val knowledge: KnowledgeRepository,
    override val outcomes: OutcomeMemoryRepository,
) : MemoryStore
