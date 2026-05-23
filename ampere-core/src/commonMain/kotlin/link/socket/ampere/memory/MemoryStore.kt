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
 * ## Agent-scoped persistence
 *
 * Implementations are expected to scope persistence to the owning agent /
 * user. Ampere does not pass an `AgentId` through the repository calls — it
 * treats each `MemoryStore` as already bound to whatever scope the consumer
 * defines (typically per-user, per-installation on Socket clients). When
 * Socket needs per-user durable on-device memory, it constructs a new
 * `MemoryStore` instance per user and passes it to Ampere.
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
     * storage scoped to the owning agent / user.
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
