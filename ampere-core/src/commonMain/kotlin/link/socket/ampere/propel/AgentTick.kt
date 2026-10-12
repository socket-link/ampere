package link.socket.ampere.propel

import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task

/**
 * The calls a driver makes for one agent's tick: PERCEIVE's, PLAN's and EXECUTE's
 * (AMPR-442, H19).
 *
 * [runAgentTick] owns everything about a tick except these calls: the order, the
 * brackets, the assignment and the release. The driver supplies the calls because that
 * is where blocking happens. The Arc reaches its agents' phase services through
 * `perceiveState`, `determinePlanForTask` and `executePlan`, and each of those goes
 * through a `runLLMTo*` lambda that wraps the service in `runBlockingCompat`. Those calls
 * stay on the driver's side, so nothing in `propel/` blocks a thread
 * (`PropelPackagePurityTest`).
 *
 * RECALL is not here. `AutonomousAgent.recallForTask` is a suspending store query with no
 * model call and nothing to block on, so the tick makes it itself.
 */
internal interface TickPhases {

    /** PERCEIVE: what the agent makes of its current state, as the ideas PLAN is given. */
    suspend fun perceive(): List<Idea>

    /** PLAN: [task] planned from [ideas] and what RECALL found. */
    suspend fun plan(
        task: Task,
        ideas: List<Idea>,
        relevantKnowledge: List<KnowledgeWithScore>,
    ): Plan

    /** EXECUTE: [plan] walked, settled to one [Outcome]. */
    suspend fun execute(plan: Plan): Outcome
}

/**
 * One agent's tick: PERCEIVE → RECALL → OBSERVE → PLAN → EXECUTE on [agent], with [task]
 * assigned (AMPR-442, H19).
 *
 * This is the body that `FlowPhase.executeAgentTickTyped` used to hold. `FlowPhase` keeps
 * the multi-agent concerns: which agent goes next, the barrier, `currentTick`, the goal
 * tree, and which goal [task] is derived from. It calls this once per agent per turn.
 * What happens to the returned outcome is the driver's business too. The Arc records it
 * in its shared context and asks [GoalCompletion] about it.
 *
 * Returns null when [task] is [Task.Blank]. The tick then ends before the Perceive call,
 * because a blank task plans `Plan.blank`, which executes to `Outcome.blank`. That is not
 * a success, so such a tick could only burn budget (AMPR-395).
 *
 * Otherwise [task] is assigned with `rememberNewTask` first. Some phases read the agent's
 * state rather than their arguments: `defaultPerceptionContext` renders the current memory
 * cell's task, and an unassigned cell asks Perceive to reason about "(none assigned yet)"
 * (AMPR-403).
 *
 * Every phase is entered through the agent's own phase manager (AMPR-441), so each bracket
 * leaves through the agent's door stamped with its `currentRunId`. The calls [phases]
 * makes open no phase of their own. One that did would nest inside the bracket here, on
 * the same manager, and show in the trace as a duplicate. There is no LEARN: the Arc's
 * `PulsePhase` enters LEARN once per run, not once per tick (AMPR-402).
 */
internal suspend fun runAgentTick(
    agent: AutonomousAgent<*>,
    task: Task,
    phases: TickPhases,
): Outcome? {
    if (task is Task.Blank) {
        return null
    }

    agent.rememberNewTask(task)

    val manager = agent.phaseSparkManager

    val plan = try {
        val ideas = manager.withPhase(CognitivePhase.PERCEIVE) {
            phases.perceive()
        }

        // What the agent's memory holds for this task, asked the agent's way: through
        // `recallForTask`, so the query is `memoryContextForTask`'s rather than a copy of it.
        val relevantKnowledge = manager.withPhase(CognitivePhase.RECALL) {
            agent.recallForTask(task)
        }

        observe(manager)

        manager.withPhase(CognitivePhase.PLAN) {
            phases.plan(task, ideas, relevantKnowledge)
        }
    } finally {
        // Close the assignment out while the cell still holds it. This is the only moment
        // `finishCurrentTask` can release the `TaskSpark` that `rememberNewTask` pushed.
        // `executePlan` records each plan step as the current task, so by the time it
        // returns the cell names a step, and the task's spark would be stranded in the
        // system prompt for the rest of the run. Execute does not need the assignment in
        // the cell, because `executeTaskWithSpark` pushes and pops a spark per step.
        agent.finishCurrentTask()
    }

    return manager.withPhase(CognitivePhase.EXECUTE) {
        phases.execute(plan)
    }
}

/**
 * OBSERVE, entered and left with nothing in it (AMPR-441).
 *
 * The bracket is here so the trace says the tick passed through the phase. A phase
 * entered and found empty is a different fact from a phase skipped, and only the first is
 * true of this tick. What OBSERVE would read has no source on the Arc path yet. F18's
 * OBSERVE runs Probes over perceived canon, and nothing on the Arc path perceives canon.
 * Nor does an Arc agent declare Probes of its own: `ArcAgentConfig` carries a role, sparks
 * and a rung floor, and nothing else. The one driver that runs declared Probes today is a
 * hosted run (`RosterHostedRun.observe`, over the run's own progress). This bracket is
 * where that work lands once the Arc path has an input for it. Until then PLAN is given
 * nothing from OBSERVE, as on every other driver.
 */
private suspend fun observe(manager: PhaseSparkManager<*>) {
    manager.withPhase(CognitivePhase.OBSERVE) {}
}
