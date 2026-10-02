package link.socket.ampere.room

import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.probe.Verdict as ProbeVerdict

/**
 * A Room rendered as text, thread by thread (AMPR-379).
 *
 * The instrument the fixture replay reads: deterministic for a given history (no
 * wall-clock, stable ordering), and honest about state — a thread waiting for the
 * human says so, and an undetermined verdict never reads as a pass.
 */
object RoomTranscript {

    fun render(threads: List<RoomThread>, messages: List<RoomMessage>): String = buildString {
        val roomId = threads.firstOrNull()?.roomId ?: messages.firstOrNull()?.roomId
        appendLine("# Room ${roomId?.value ?: "(empty)"}")

        val byThread = messages.groupBy { it.threadId }
        val known = threads.map { it.threadId }.toSet()

        threads.forEach { thread ->
            appendLine()
            appendLine(heading(thread))
            byThread[thread.threadId].orEmpty().forEach { appendLine(line(it)) }
        }

        // Messages whose thread the Room could not list still belong in the record.
        byThread.filterKeys { it !in known }.forEach { (threadId, orphaned) ->
            appendLine()
            appendLine("## [unknown] $threadId")
            orphaned.forEach { appendLine(line(it)) }
        }
    }

    private fun heading(thread: RoomThread): String = buildString {
        append("## ")
        append(
            when (val subject = thread.subject) {
                null -> "[thread] ${thread.threadId}"
                ThreadSubject.General -> "[general]"
                is ThreadSubject.Milestone -> "[milestone] ${subject.milestoneId.value}"
                is ThreadSubject.Verdict -> "[verdict] ${subject.subjectId} (${subject.kind.name.lowercase()})"
                is ThreadSubject.Hazard ->
                    "[hazard] ${subject.subjectId} (${subject.category.name.lowercase()})"
            },
        )
        append(" — ")
        append(
            when (thread.status) {
                EventStatus.Open -> "open"
                EventStatus.WaitingForHuman -> "waiting for human"
                EventStatus.Resolved -> "resolved"
            },
        )
        thread.assignedTo?.let { append(" · assigned to ${it.value}") }
    }

    private fun line(message: RoomMessage): String = buildString {
        append("  ")
        append(message.author.label)
        append(": ")
        append(message.body.lineSequence().joinToString(" ").trim())
        message.card?.let { card ->
            append("  [")
            append(describe(card))
            append("]")
        }
    }

    private fun describe(card: RoomCard): String = when (card) {
        is RoomCard.PlanRevision -> buildString {
            append("revision ${card.revision}: ${card.remainingItems} items, ${card.remaining} remaining")
            append(", finish ${card.projectedFinish?.toString() ?: "unscheduled"}")
        }
        is RoomCard.Verdict -> "verdict ${card.probeId.value} on ${card.subjectId}: ${card.verdict.label()}"
        is RoomCard.Hazard -> buildString {
            append("hazard ${card.category.name} on ${card.subjectId}: ${card.mitigationHint.name}")
            card.mitigationTaskId?.let { append(" -> ${it.value}") }
        }
        is RoomCard.Status -> buildString {
            append("status: ${card.completed} done, ${card.remaining} remaining, ${card.blocked} blocked")
            append(", finish ${card.projectedFinish?.toString() ?: "unscheduled"}")
        }
    }

    private fun ProbeVerdict.label(): String = when (this) {
        is ProbeVerdict.Holds -> "holds"
        is ProbeVerdict.Warn -> "warn"
        is ProbeVerdict.Violated -> "violated"
        is ProbeVerdict.Undetermined -> "undetermined(${cause.name})"
    }
}
