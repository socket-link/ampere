package link.socket.ampere.agents.domain.knowledge

import link.socket.ampere.field.Belief
import link.socket.ampere.field.Decay
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref

/**
 * This stored knowledge row as the Field's [Belief] about [subject] (AMPR-439).
 *
 * A row with a confidence is [Belief.Settled]; one without is [Belief.Unsettled] — nothing
 * reported a confidence, so nothing ranks it. Holder and sensitivity come from the row as
 * stored, `system` for a backfilled one. The provenance is the row's `created_by_event` and its
 * timestamp. The content is the approach and what was learned from it, as prose, in that
 * order: a lesson read without the attempt it came from loses what it is a lesson about.
 *
 * **Why [subject] is a parameter.** The knowledge table has no subject column: what a belief is
 * *about* is the caller's to say. The Field read passes the root goal of the row's run when the
 * run formed one, and the holder itself otherwise.
 *
 * A row that names no event — every row older than `10.sqm`, and a write that published none —
 * is a failure with [UnprovenancedKnowledgeException], never a belief with a made-up
 * provenance: where a belief came from is the one thing the Field cannot recover later.
 *
 * Lives here rather than in `field/`, which may not import `agents.domain`.
 */
fun KnowledgeEntry.toBelief(subject: Ref<Primitive>): Result<Belief> {
    val createdBy = createdByEvent
        ?: return Result.failure(UnprovenancedKnowledgeException(id))
    val provenance = Provenance(createdBy = createdBy, createdAt = timestamp)
    val content = listOf(approach, learnings).filter { it.isNotBlank() }.joinToString("\n\n")
    val belief = if (confidence != null) {
        Belief.Settled(
            holder = holder,
            subject = subject,
            content = content,
            confidence = confidence,
            sensitivity = sensitivity,
            decay = Decay.None,
            provenance = provenance,
        )
    } else {
        Belief.Unsettled(
            holder = holder,
            subject = subject,
            content = content,
            sensitivity = sensitivity,
            decay = Decay.None,
            provenance = provenance,
        )
    }
    return Result.success(belief)
}

/**
 * A knowledge row names no `created_by_event`, so it has no provenance a [Belief] could carry.
 * Every row written before `10.sqm` is one.
 */
class UnprovenancedKnowledgeException(
    val knowledgeId: String,
) : Exception("Knowledge '$knowledgeId' names no event that recorded it, so it cannot be read as a Belief")
