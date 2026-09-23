package link.socket.ampere.domain.arc

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.OutcomeId

/**
 * The durable form of a [CompletionManifest] (AMPR-359): what a cancelled or failed run's manifest
 * becomes when it is written to the event store, and what `ArcTraceProjection` folds back out as
 * `ArcRunTrace.completion`.
 *
 * ### What is kept, and what is not
 *
 * The record is written on the teardown path, so its size is set by the run's shape — its agents,
 * its goal tree — and never by how long the run went on or what it happened to produce:
 * - **Outcomes** become one [OutcomeTally] per agent: how many, how many succeeded or failed, and
 *   the ids of the last [MAX_RECENT_OUTCOME_IDS]. An `Outcome` carries whole tasks, plans and tool
 *   results; the record says what was produced, not what it contained.
 * - **Goals** keep their id and description, flattened. A `GoalNode` repeats its whole subtree, so
 *   the tree's shape stays with Charge. Descriptions are clipped to [MAX_GOAL_DESCRIPTION_CHARS].
 * - **The failure** keeps its `Type: message` line, clipped to [MAX_FAILURE_CHARS]: a message can
 *   embed a whole payload.
 * - How the run ended, its phases and the tick it reached are kept whole.
 *
 * Anything clipped ends in `…`, so a cut reads as a cut. The manifest's honest floor carries over
 * unchanged: [unmetGoals] is `null` when the intended goals are unknown, never an empty list.
 *
 * @property runId The run this record closes out.
 * @property endedBy [TerminationReason.CANCELLED] or [TerminationReason.ERROR].
 * @property failure The `Type: message` of the throwable that ended an [TerminationReason.ERROR]
 *   run, clipped; `null` for a cancelled one.
 * @property phasesStarted Phases the run entered, in order.
 * @property phasesCompleted Phases that ran to their end. Never contains [ArcPhase.PULSE].
 * @property phasesNotRun Phases the run never entered.
 * @property reachedTick The number of Flow ticks that completed, or `null` if Flow never started.
 * @property producedOutcomes One tally per agent that recorded an outcome, in the order they first did.
 * @property completedGoals Goals the Flow marked complete before the run ended.
 * @property unmetGoals Intended goals that did not happen, or `null` if they are unknown.
 */
@Serializable
data class CompletionRecord(
    val runId: RunId,
    val endedBy: TerminationReason,
    val failure: String? = null,
    val phasesStarted: List<ArcPhase> = emptyList(),
    val phasesCompleted: List<ArcPhase> = emptyList(),
    val phasesNotRun: List<ArcPhase> = emptyList(),
    val reachedTick: Int? = null,
    val producedOutcomes: List<OutcomeTally> = emptyList(),
    val completedGoals: List<Goal> = emptyList(),
    val unmetGoals: List<Goal>? = null,
) {
    /** The phase the run was in, or about to enter, when it ended. See [CompletionManifest.endedDuring]. */
    val endedDuring: ArcPhase
        get() = firstUnfinishedPhase(phasesCompleted)

    /** Whether the set of intended goals is known, i.e. Charge got far enough to build a goal tree. */
    val intendedGoalsKnown: Boolean
        get() = unmetGoals != null

    /** The same line [CompletionManifest.summary] gives for the manifest this record was made from. */
    fun summary(): String = completionSummary(
        endedBy = endedBy,
        endedDuring = endedDuring,
        reachedTick = reachedTick,
        goalsMet = completedGoals.size,
        goalsUnmet = unmetGoals?.size,
        phasesNotRun = phasesNotRun,
    )

    /** One goal of the run's goal tree, without its subtree. */
    @Serializable
    data class Goal(
        val id: String,
        val description: String,
    )

    /**
     * What one agent produced before the run ended.
     *
     * @property total Every outcome the agent recorded, blank ones included.
     * @property succeeded Outcomes that were an `Outcome.Success`.
     * @property failed Outcomes that were an `Outcome.Failure`.
     * @property recentIds Ids of the agent's last [MAX_RECENT_OUTCOME_IDS] outcomes that carry one,
     *   oldest first — enough to find the latest in memory or in the trace, not an inventory.
     */
    @Serializable
    data class OutcomeTally(
        val agentId: String,
        val total: Int,
        val succeeded: Int,
        val failed: Int,
        val recentIds: List<OutcomeId> = emptyList(),
    )

    companion object {
        /** Longest [failure] kept, in characters. */
        const val MAX_FAILURE_CHARS: Int = 1_000

        /** Longest [Goal.description] kept, in characters. */
        const val MAX_GOAL_DESCRIPTION_CHARS: Int = 200

        /** Most [OutcomeTally.recentIds] kept per agent. */
        const val MAX_RECENT_OUTCOME_IDS: Int = 10

        /** The reduction behind [CompletionManifest.toRecord]. */
        internal fun of(manifest: CompletionManifest): CompletionRecord = CompletionRecord(
            runId = manifest.runId,
            endedBy = manifest.endedBy,
            failure = manifest.failure?.clip(MAX_FAILURE_CHARS),
            phasesStarted = manifest.phasesStarted,
            phasesCompleted = manifest.phasesCompleted,
            phasesNotRun = manifest.phasesNotRun,
            reachedTick = manifest.reachedTick,
            producedOutcomes = manifest.producedOutcomes.map { (agentId, outcomes) -> tally(agentId, outcomes) },
            completedGoals = manifest.completedGoals.map(::goal),
            unmetGoals = manifest.unmetGoals?.map(::goal),
        )

        private fun goal(node: GoalNode): Goal = Goal(
            id = node.id,
            description = node.description.clip(MAX_GOAL_DESCRIPTION_CHARS),
        )

        private fun tally(agentId: String, outcomes: List<Outcome>): OutcomeTally = OutcomeTally(
            agentId = agentId,
            total = outcomes.size,
            succeeded = outcomes.count { it is Outcome.Success },
            failed = outcomes.count { it is Outcome.Failure },
            recentIds = outcomes
                .map { it.id }
                .filter { it.isNotBlank() }
                .takeLast(MAX_RECENT_OUTCOME_IDS),
        )

        /** At most [max] characters of this string, ending in `…` when anything was dropped. */
        private fun String.clip(max: Int): String = if (length <= max) this else take(max - 1) + "…"
    }
}
