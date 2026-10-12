package link.socket.ampere.propel

import link.socket.ampere.agents.domain.outcome.Outcome

/**
 * Whether the goal a driver is working on is complete, given the outcome of the work it
 * just did (AMPR-442, H19).
 *
 * Both drivers ask this one question with the same input. `FlowPhase` asks it after each
 * agent's tick, with the outcome that tick's EXECUTE settled to. A yes marks the Arc's
 * current goal complete and moves the Arc to the next incomplete node. A roster-hosted
 * run asks it after each cycle that executed something, with the outcome that cycle's
 * plan walk settled to. The run records the answer and never reads it, so the run
 * continues or ends exactly as it would without one. Whether a run should end on its own
 * judgment of completion is not decided by H19.
 *
 * This is the seat for AMPR-381 row J9: the first in-loop judgment. A measured judgment
 * replaces [AnySuccess] here, behind this interface, and both drivers get it at once
 * because both call the same thing. That is why the call is `suspend` even though the
 * default needs no suspension: a judgment is a decision call. Nothing is wired yet, and
 * the default stays deterministic.
 */
internal interface GoalCompletion {

    /** True when [outcome] completes the goal it was produced for. */
    suspend fun isComplete(outcome: Outcome): Boolean

    companion object {

        /**
         * The deterministic default: any [Outcome.Success] completes the goal, and
         * nothing else does.
         *
         * This is the Arc's rule unchanged. `FlowPhase.evaluateGoalCompletion` marked the
         * current goal complete on the first success of any kind, and nothing checked
         * that the goal was actually met. A held outcome, a blank one or a failure is
         * not a success, so none of them completes a goal.
         */
        val AnySuccess: GoalCompletion = object : GoalCompletion {
            override suspend fun isComplete(outcome: Outcome): Boolean = outcome is Outcome.Success
        }
    }
}
