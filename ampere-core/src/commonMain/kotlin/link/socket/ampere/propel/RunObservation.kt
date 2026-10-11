package link.socket.ampere.propel

import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.api.AmpereStableApi

/**
 * The run as OBSERVE finds it: what it is for, which cycle this is, and what has run
 * so far (AMPR-393).
 *
 * The subject the verifier seat's [Probe][link.socket.ampere.probe.Probe]s are
 * evaluated against. `Probe<in S>` is contravariant and its subject is deliberately
 * unconstrained, so the shipped Probes take their own subjects — a `CanonWorkGraph`,
 * an `Observed`, a `WorkPlanSubject` — none of which a run can synthesise from a
 * goal. This is the subject a run *can* describe, and it is the one a Probe that
 * wants to hold a run to a precondition ("there is a plan to check", "the last cycle
 * produced something") needs.
 *
 * It is a snapshot, not a handle: holding one after OBSERVE returns tells you what
 * the run looked like then, which is the point — a verdict is about a moment.
 *
 * @property runId The run being observed. Also the `subjectId` its verdicts carry.
 * @property goal What the run is for.
 * @property cycle Which OBSERVE this is, counting from 1. Greater than 1 means the
 *   run has already executed a plan and is observing before planning again.
 * @property priorResults Every step the run has executed, oldest first. Empty on the
 *   first cycle, by construction: nothing has run.
 */
@AmpereStableApi
data class RunObservation(
    val runId: RunId,
    val goal: Task,
    val cycle: Int,
    val priorResults: List<StepOutcome> = emptyList(),
)
