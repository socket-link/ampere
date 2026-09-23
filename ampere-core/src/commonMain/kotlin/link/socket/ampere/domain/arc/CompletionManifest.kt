package link.socket.ampere.domain.arc

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.ArcRunEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.trace.ArcRunId

/** The three phases of an Arc lifecycle, in the order they run. */
enum class ArcPhase {
    CHARGE,
    FLOW,
    PULSE,
}

/**
 * The completion record of an Arc run that ended before it closed its loop (AMPR-282) — one that
 * was cancelled, or one where a phase threw.
 *
 * ### Why this is not a `Knowledge` entry
 *
 * The PropelLoop invariant is that a run closes through `Knowledge`, and Pulse is the only place
 * `Knowledge` is captured. A cancelled or failed run does not finish Pulse, so it genuinely did
 * **not** close the loop — and writing a `Knowledge` entry for it would claim that it had. This is
 * a distinct record type on purpose: the invariant keeps its teeth ("no `Knowledge`, loop not
 * closed"), and the run still leaves an honest account of what did and did not happen.
 *
 * It is a record, not a checkpoint: nothing here is meant to resume the run.
 *
 * ### Honest floor
 *
 * Every field records what the runtime demonstrably has, and says so when it has nothing. A
 * `null` means *unknown*, never *none*: [unmetGoals] is `null` when Charge never produced a goal
 * tree, because an empty list would read as "every intended goal was met".
 *
 * @property runId The run this manifest closes out.
 * @property endedBy How the run ended — [TerminationReason.CANCELLED] or [TerminationReason.ERROR].
 *   The existing Flow vocabulary on purpose, rather than a second fate taxonomy layered over it.
 * @property failure Type and message of the throwable that ended an [TerminationReason.ERROR] run;
 *   `null` for a cancelled one.
 * @property phasesStarted Phases the run entered, in order.
 * @property phasesCompleted Phases that ran to their end. Never contains [ArcPhase.PULSE].
 * @property phasesNotRun Phases the run never entered. Contains [ArcPhase.PULSE] unless the run
 *   ended while Pulse itself was running — which is what makes the missing `Knowledge` explicit
 *   rather than inferred.
 * @property reachedTick The number of Flow ticks that completed, or `null` if Flow never started.
 * @property producedOutcomes Every agent outcome recorded before the run ended, keyed by agent id.
 * @property completedGoals Goals the Flow marked complete before the run ended.
 * @property unmetGoals Intended goals that did not happen, or `null` if the goal tree was never
 *   built and so the intended goals are unknown.
 */
data class CompletionManifest(
    val runId: ArcRunId,
    val endedBy: TerminationReason,
    val failure: String?,
    val phasesStarted: List<ArcPhase>,
    val phasesCompleted: List<ArcPhase>,
    val phasesNotRun: List<ArcPhase>,
    val reachedTick: Int?,
    val producedOutcomes: Map<String, List<Outcome>>,
    val completedGoals: List<GoalNode>,
    val unmetGoals: List<GoalNode>?,
) {
    /**
     * The phase the run was in, or about to enter, when it ended: the first phase that did not
     * complete. Phases run strictly in order, so everything before it finished.
     */
    val endedDuring: ArcPhase
        get() = firstUnfinishedPhase(phasesCompleted)

    /** Whether the set of intended goals is known, i.e. Charge got far enough to build a goal tree. */
    val intendedGoalsKnown: Boolean
        get() = unmetGoals != null

    /** One line for logs and status bars: where the run stopped and what it left undone. */
    fun summary(): String = completionSummary(
        endedBy = endedBy,
        endedDuring = endedDuring,
        reachedTick = reachedTick,
        goalsMet = completedGoals.size,
        goalsUnmet = unmetGoals?.size,
        phasesNotRun = phasesNotRun,
    )

    /**
     * The bounded, serializable form of this manifest that is persisted (AMPR-359). See
     * [CompletionRecord] for what it keeps of each field and why.
     */
    fun toRecord(): CompletionRecord = CompletionRecord.of(this)

    /**
     * The event that carries [toRecord] into the event store and onto the bus, stamped [timestamp]
     * and attributed to [eventSource]. A failed run is published at [Urgency.HIGH], a cancelled one
     * at [Urgency.MEDIUM].
     */
    fun toEvent(eventSource: EventSource, timestamp: Instant): ArcRunEvent.CompletionManifestRecorded =
        ArcRunEvent.CompletionManifestRecorded(
            eventId = generateUUID("completion-manifest", runId),
            timestamp = timestamp,
            eventSource = eventSource,
            record = toRecord(),
            urgency = if (endedBy == TerminationReason.ERROR) Urgency.HIGH else Urgency.MEDIUM,
        )

    companion object {
        /**
         * Build the manifest from whatever state an unfinished run left behind.
         *
         * @param endedBy [TerminationReason.CANCELLED] or [TerminationReason.ERROR].
         * @param cause The throwable that ended an [TerminationReason.ERROR] run; ignored otherwise.
         * @param reachedPhase The furthest phase the run entered, or `null` if none.
         * @param chargeResult Non-null only if Charge finished.
         * @param flowResult Flow's returned result, or its snapshot if it was cut short; non-null
         *   if Flow started.
         * @param flowCompleted Whether Flow returned on its own, as opposed to being snapshotted.
         */
        internal fun fromIncompleteRun(
            runId: ArcRunId,
            endedBy: TerminationReason,
            cause: Throwable?,
            reachedPhase: ArcPhase?,
            chargeResult: ChargeResult?,
            flowResult: FlowResult?,
            flowCompleted: Boolean,
        ): CompletionManifest {
            require(endedBy == TerminationReason.CANCELLED || endedBy == TerminationReason.ERROR) {
                "A completion manifest records an unfinished run, not $endedBy"
            }

            val started = reachedPhase?.let { ArcPhase.entries.take(it.ordinal + 1) }.orEmpty()
            val completed = buildList {
                if (chargeResult != null) add(ArcPhase.CHARGE)
                if (flowCompleted) add(ArcPhase.FLOW)
            }
            val completedGoals = flowResult?.completedGoals.orEmpty()

            return CompletionManifest(
                runId = runId,
                endedBy = endedBy,
                failure = cause
                    ?.takeIf { endedBy == TerminationReason.ERROR }
                    ?.let { "${it::class.simpleName ?: "Throwable"}: ${it.message}" },
                phasesStarted = started,
                phasesCompleted = completed,
                phasesNotRun = ArcPhase.entries - started.toSet(),
                reachedTick = flowResult?.finalTick,
                producedOutcomes = flowResult?.agentOutcomes.orEmpty(),
                completedGoals = completedGoals,
                unmetGoals = chargeResult?.goalTree?.allNodes()?.filterNot { it in completedGoals },
            )
        }
    }
}

/**
 * The first phase not in [completed]. Phases run strictly in order, so everything before it
 * finished — which is what makes it the phase an unfinished run ended during.
 */
internal fun firstUnfinishedPhase(completed: List<ArcPhase>): ArcPhase =
    ArcPhase.entries.first { it !in completed }

/**
 * The one-line account shared by [CompletionManifest.summary] and [CompletionRecord.summary], so a
 * persisted record reads exactly as the manifest it was made from did.
 *
 * @param goalsUnmet `null` when the intended goals are unknown, which the line says rather than
 *   reporting a total it does not have.
 */
internal fun completionSummary(
    endedBy: TerminationReason,
    endedDuring: ArcPhase,
    reachedTick: Int?,
    goalsMet: Int,
    goalsUnmet: Int?,
    phasesNotRun: List<ArcPhase>,
): String = buildString {
    append(if (endedBy == TerminationReason.CANCELLED) "cancelled" else "failed")
    append(" during ${endedDuring.name}")
    reachedTick?.let { append(" at tick $it") }
    if (goalsUnmet == null) {
        append("; intended goals unknown")
    } else {
        append("; $goalsMet/${goalsMet + goalsUnmet} goals met")
    }
    append("; not run: ${phasesNotRun.joinToString { it.name }}")
}
