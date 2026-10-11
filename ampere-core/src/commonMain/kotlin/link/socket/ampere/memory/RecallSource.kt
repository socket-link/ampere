package link.socket.ampere.memory

import link.socket.ampere.api.AmpereStableApi

/**
 * A corpus a run recalls from that is not the agent's own memory (AMPR-385 row H4b).
 *
 * Recall inside Ampere reads [MemoryStore.knowledge] — what this agent learned, in
 * Ampere's own `(approach, learnings)` shape. A consumer also keeps a corpus of its
 * own: the documents a person saved, their notes, their files, scoped by tags. An
 * agent should be able to recall from that too, and Ampere should not have to know
 * the store's shape to let it.
 *
 * So this is the whole contract: one question in, a scored list out. The retrieval
 * is the implementation's — embeddings, full-text, a remote index, a hand-written
 * filter are all the same to the caller — which is why there is no embedding or
 * search inside Ampere to configure.
 *
 * It sits beside [MemoryStore] rather than on it, deliberately. A `MemoryStore` is
 * the agent's own two repositories, read *and written* by the loop; a `RecallSource`
 * is read-only, and a consumer that keeps a corpus need not also own the agent's
 * memory. Putting the method on `MemoryStore` would have made every implementer of
 * durable agent memory answer for a corpus it may not have.
 *
 * ```kotlin
 * val run = ampere.runs!!.open(
 *     roster = roster,
 *     seats = mapOf(
 *         RoleId("planner") to HostedAgent(
 *             /* … */
 *             recallScopes = setOf("recipes", "pantry"),
 *         ),
 *     ),
 *     goal = goal,
 *     policy = RunPolicy(recall = RecallPolicy(sources = listOf(myDocuments))),
 * )
 * ```
 *
 * ## What a hosted run does with one
 *
 * RECALL asks every source the run was given, once, for at most
 * [RecallPolicy.perSourceLimit][link.socket.ampere.propel.RecallPolicy.perSourceLimit]
 * items, and merges what comes back with what the seat's own memory found: both
 * halves reach the agent as the prompt's "# Recalled context" section and both are
 * summarised in the one `KnowledgeRecalled` the phase publishes. Nothing is stored —
 * a recalled document is the consumer's, and copying it into Ampere's knowledge store
 * would make a second home for it that LEARN would then keep writing to.
 *
 * A source is only asked when the seat declares scopes to ask under
 * ([HostedAgent.recallScopes][link.socket.ampere.propel.HostedAgent.recallScopes]):
 * an unscoped corpus query is a request for the whole corpus, which is not a recall.
 *
 * ## Implementing one
 *
 * Be cheap and be honest. [recall] is called once per run, inside the RECALL phase,
 * and the phase waits for it — there is no timeout around it, because the run's
 * caller owns the run's time. An exception propagates: it fails RECALL, and so fails
 * the `RunHost.open` that charged it, the same way a consumer's
 * [Probe][link.socket.ampere.probe.Probe] that throws fails OBSERVE. A source whose
 * backing store may be unreachable should decide for itself whether that is an empty
 * recall or a failed run, and return `emptyList()` if it is the former.
 *
 * @see MemoryStore
 */
@AmpereStableApi
fun interface RecallSource {

    /**
     * The at most [limit] items of this corpus most relevant to [intent].
     *
     * @param intent What the run is trying to do, in words — a hosted run passes its
     *   goal's description. This is the query.
     * @param scopes The tags to look under, as the seat declared them. Never empty:
     *   a run with no scopes does not call this at all.
     * @param limit The most items to return. Returning more is not an error and the
     *   caller takes the first [limit] of them, but the caller has no way to tell a
     *   good 20th hit from a bad one, so ranking is better done here.
     * @return What was found, best first. Empty when nothing matched, which is a
     *   normal answer and not a failure.
     */
    suspend fun recall(intent: String, scopes: Set<String>, limit: Int): List<Recalled>
}

/**
 * One item a [RecallSource] found.
 *
 * Deliberately not a [link.socket.ampere.agents.domain.knowledge.Knowledge]: a
 * `Knowledge` entry is something an agent distilled from its own work and can file
 * under a task type, and a recalled document is neither. What the two have in common
 * is the shape a prompt needs — something to call it, something it says — and that is
 * all this carries.
 *
 * @property id This item's own id in the source. Stable across recalls, so a caller
 *   can tell the same item returned twice; otherwise opaque to Ampere.
 * @property title What to call it: a document title, a note's first line, a filename.
 *   Rendered as the first half of the item's line in the prompt, clipped to
 *   [RecallPolicy.titleClip][link.socket.ampere.propel.RecallPolicy.titleClip].
 * @property text What it says, or the part of it that matched. Rendered as the second
 *   half of the line, clipped to
 *   [RecallPolicy.textClip][link.socket.ampere.propel.RecallPolicy.textClip] — so a
 *   source is free to return a whole document and let the clip do the work, though
 *   returning the passage that actually matched spends the budget better.
 * @property score How relevant the source thinks it is, higher being more. Used for
 *   ordering only: no range is assumed and none is enforced, because a source's
 *   scores are its own and Ampere cannot calibrate them against the relevance
 *   `AgentMemoryService` computes for its own entries.
 * @property sourceId The document this item came out of, when the source chunks its
 *   corpus and [id] names a chunk rather than the whole. Null when there is no
 *   narrower origin to name, which is the common case. It is what the run puts on the
 *   `KnowledgeRecalled` summary as the item's source, falling back to [id].
 */
@AmpereStableApi
data class Recalled(
    val id: String,
    val title: String,
    val text: String,
    val score: Double,
    val sourceId: String? = null,
)
