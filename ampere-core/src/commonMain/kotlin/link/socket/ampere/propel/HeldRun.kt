package link.socket.ampere.propel

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.api.AmpereStableApi

/**
 * Everything a held run needs to carry on where it stopped (AMPR-430, AMPR-385 row H30).
 *
 * A run whose tool reported its action is waiting on a person ends [RunStatus.Held]
 * (AMPR-425), and it resumes by carrying on with the plan that was already approved, not
 * by planning again (row H29). What the run *has done* is already durable — every
 * `PlanStepCompleted` is persisted before it is dispatched — but what it *was going to do*
 * lived only in the run's own fields. This is that state as a value, for [HeldRunStore] to
 * keep while the person decides, which can be long after the process that ran the run is
 * gone.
 *
 * It holds step results, which can contain a person's content. It sits beside the event
 * store, which holds the same results, and is not to be copied anywhere else, logged, or
 * put on an event.
 *
 * @property runId The run, and the record's address in [HeldRunStore].
 * @property hostRole The roster role that plans — `Roster.host` — as its id string.
 * @property hostAgentId The agent that filled the host seat, which is also its event door's
 *   identity.
 * @property goal The run's goal, as it was handed to `RunHost.open`.
 * @property cycle The cycle that held, counted from 1.
 * @property plan The held cycle's plan as it passed the [PlanGate] — possibly trimmed by it.
 * @property results Every step outcome of the run so far, oldest first: earlier cycles,
 *   this cycle's finished steps, the held step, and the [StepOutcome.Skipped] steps after it.
 * @property heldStepId The id of the step that held.
 * @property reason What is being waited on, for a person to read.
 * @property reference The opaque string the tool put on its held outcome — the link to
 *   whatever owns the decision (a consumer's own record, or a `PauseStore` correlation id).
 *   Carried, never read.
 * @property ideas The host seat's ideas from PERCEIVE, which later cycles plan from.
 * @property seatsThatRan The roles, as id strings, of every seat that executed a step so far
 *   — the seats LEARN extracts from.
 * @property heldAt When the run held.
 */
@AmpereStableApi
@Serializable
data class HeldRun(
    val runId: RunId,
    val hostRole: String,
    val hostAgentId: AgentId,
    val goal: Task,
    val cycle: Int,
    val plan: Plan,
    val results: List<StepOutcome>,
    val heldStepId: String,
    val reason: String,
    val reference: String? = null,
    val ideas: List<Idea> = emptyList(),
    val seatsThatRan: Set<String> = emptySet(),
    val heldAt: Instant,
)

/**
 * One [HeldRun] as [HeldRunStore.list] reports it: built from the record's scalar columns
 * alone, so a record this build cannot decode is still listed, and still deletable.
 *
 * @property claimedAt When a resumer claimed the run, or null if nobody has yet. A claimed
 *   record whose resume was interrupted stays listed with this set, for its owner to deal with.
 */
@AmpereStableApi
data class HeldRunSummary(
    val runId: RunId,
    val heldStepId: String,
    val reason: String,
    val reference: String?,
    val heldAt: Instant,
    val claimedAt: Instant?,
)

/**
 * What [HeldRunStore.claim] found. Exactly one caller of a held run is ever [Claimed].
 */
@AmpereStableApi
sealed interface HeldRunClaim {

    /** This call took the run up; [run] is the record it decoded and is now the caller's. */
    data class Claimed(val run: HeldRun) : HeldRunClaim

    /** No record is stored under the run id. */
    data object NotHeld : HeldRunClaim

    /** Someone else took the run up first, at [at]. */
    data class AlreadyClaimed(val at: Instant) : HeldRunClaim
}
