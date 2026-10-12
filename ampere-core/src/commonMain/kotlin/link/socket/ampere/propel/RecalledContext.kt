package link.socket.ampere.propel

import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.RetrievedKnowledgeSummary
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.memory.retrievedSummaries
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.recalledBlock
import link.socket.ampere.memory.Recalled

/**
 * What one RECALL found, from both places it looks (AMPR-385 row H4b).
 *
 * The two halves stay separate rather than being flattened into one list, because
 * they are owed different things downstream: [fromMemory] is what PLAN is handed as
 * `relevantKnowledge` (AMPR-388) and is the only half the agent's own store knows
 * about, while [fromSources] is the consumer's and is never written anywhere. Only
 * the prompt and the event see them merged.
 *
 * @property fromMemory The seat's own knowledge, scored by `AgentMemoryService`.
 * @property fromSources What the run's [link.socket.ampere.memory.RecallSource]s
 *   returned, best first, already cut to the per-source limit.
 */
internal data class RecalledContext(
    val fromMemory: List<KnowledgeWithScore> = emptyList(),
    val fromSources: List<Recalled> = emptyList(),
) {

    /** Whether RECALL found nothing at all, in which case there is no section to render. */
    val isEmpty: Boolean get() = fromMemory.isEmpty() && fromSources.isEmpty()
}

/**
 * The "# Recalled context" section of a seat's active prompt, or null when RECALL
 * found nothing.
 *
 * One line per item, best first: what it was called, then what it says, each clipped.
 * Both halves are ordered by their own score and interleaved by it — the two scales
 * are not calibrated against each other and Ampere cannot calibrate them, which is
 * why ranking across sources beyond score order is out of scope (AMPR-394). A tie
 * keeps memory first, because `sortedByDescending` is stable.
 *
 * The instruction above the list is one sentence and does two jobs: it stops the model
 * treating a weak hit as an obligation, and it stops it reciting the section back to
 * the person as if it were an answer.
 *
 * Returns null — not an empty section — when there is nothing to say. A heading over
 * no items reads as "the agent remembered nothing relevant", which is a claim this
 * has no standing to make: a seat with no memory and no sources did not look.
 *
 * The items sit inside the delimited data block, each labelled `[external]` (AMPR-453):
 * a consumer's corpus is outside the run, and a stored learning can quote what an earlier
 * run's tools read. Because the section reaches every seat's prompt, a run that renders
 * one has shown a model outside content — [RosterHostedRun] raises
 * [HostedRun.readExternalContent] when it does.
 */
internal fun RecalledContext.promptSection(titleClip: Int, textClip: Int): String? {
    if (isEmpty) return null

    val lines = (fromMemory.map { it.asLine() } + fromSources.map { it.asLine() })
        .sortedByDescending { it.score }

    return buildString {
        appendLine("# Recalled context")
        appendLine()
        appendLine(
            "Drawn from past work and saved material; use it only where it bears on the " +
                "task at hand, and never repeat it back.",
        )
        appendLine()
        append(
            recalledBlock(
                lines.map { line -> "${line.title.clipTo(titleClip)} — ${line.text.clipTo(textClip)}" },
            ),
        )
    }.trimEnd()
}

/**
 * The one [MemoryEvent.KnowledgeRecalled] a hosted run's RECALL publishes, summarising
 * both halves (AMPR-394).
 *
 * Published by the run through the host seat's own door rather than by
 * `AgentMemoryService`, which is why the service's recall is reached through its
 * non-publishing `scoreRecall`: one phase, one event, whether or not a consumer corpus
 * answered. The provenance is identical either way — the door is the seat's, so
 * `eventSource` names the seat and the envelope carries the run.
 *
 * Corpus items are typed [KnowledgeType.FROM_TASK]: of the five sources a knowledge
 * entry can record, it is the only one that means "a thing that was worked on" rather
 * than a step of this agent's own cognition, and a recalled document is not an `Idea`,
 * a `Perception`, a `Plan` or an `Outcome` of anything Ampere ran. Nothing is stored
 * under that type — the typing exists so a trace reader can tell the two halves apart.
 *
 * [MemoryEvent.KnowledgeRecalled.topKnowledgeIds] carries the memory half's entry ids
 * and then the corpus half's item ids. They are ids in different spaces, which is the
 * same thing that field already does for a recall spanning knowledge types.
 */
internal fun RecalledContext.asRecalledEvent(
    agentId: AgentId,
    context: MemoryContext,
    runId: RunId,
    timestamp: Instant,
): MemoryEvent.KnowledgeRecalled {
    val summaries = fromMemory.retrievedSummaries() + fromSources.map { it.asSummary() }
    val scores = fromMemory.map { it.relevanceScore } + fromSources.map { it.score }

    return MemoryEvent.KnowledgeRecalled(
        eventId = generateUUID("knowledge-recalled", agentId),
        timestamp = timestamp,
        eventSource = EventSource.Agent(agentId),
        context = context,
        resultsFound = fromMemory.size + fromSources.size,
        averageRelevance = if (scores.isEmpty()) 0.0 else scores.average(),
        topKnowledgeIds = fromMemory.map { it.entry.id } + fromSources.map { it.id },
        retrievedKnowledge = summaries,
        runId = runId,
    )
}

/**
 * A recalled corpus item as the event summarises it.
 *
 * The item's [Recalled.sourceId] is the document it came out of; an item whose source
 * names no narrower origin is filed under its own id, because a summary naming no
 * source is the one row of this event a reader cannot follow back.
 */
private fun Recalled.asSummary(): RetrievedKnowledgeSummary = RetrievedKnowledgeSummary(
    knowledgeType = KnowledgeType.FROM_TASK,
    approach = title,
    learnings = text,
    relevanceScore = score,
    sourceId = sourceId ?: id,
)

/** One rendered item: the two halves of its line, and the score that orders it. */
private data class RecalledLine(val title: String, val text: String, val score: Double)

private fun KnowledgeWithScore.asLine(): RecalledLine = RecalledLine(
    title = knowledge.approach,
    text = knowledge.learnings,
    score = relevanceScore,
)

private fun Recalled.asLine(): RecalledLine = RecalledLine(
    title = title,
    text = text,
    score = score,
)

/**
 * [this] on one line, at most [max] characters of it.
 *
 * Whitespace is collapsed before the cut, not after: the section is one line per item,
 * so a learning written as three paragraphs has to become one line *and* then be
 * measured, or the clip would spend its budget on newlines it is about to remove. Text
 * longer than [max] is cut there and an ellipsis appended, so a clipped fragment is
 * [max] + 1 characters and is visibly a fragment.
 *
 * `internal` rather than private because a decision call's state clips by the same
 * convention (AMPR-421, [asDecisionState]), and two definitions of "clipped" would be
 * two things to keep in step — a state whose clip drifted from the prompt's would
 * digest differently for the same run.
 */
internal fun String.clipTo(max: Int): String {
    val oneLine = trim().replace(WHITESPACE_RUN, " ")
    return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
}

private val WHITESPACE_RUN = Regex("""\s+""")
