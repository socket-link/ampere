package link.socket.ampere.propel

import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.describeResult
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.field.Admission
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Rule

/**
 * Admits one plan step and dispatches it on [agent], the seat it was handed to
 * (AMPR-442, H19).
 *
 * This is what executing a step means on both drivers. The Arc path reaches it through
 * `SparkBasedAgent.executePlanStep`, which is a one-line delegate to this, under the
 * agent's own `executePlan` walk. A roster-hosted run calls it directly from
 * [RosterHostedRun]'s EXECUTE, once per step on the seat the step was assigned to
 * (AMPR-393). With one definition there is one place for tool narrowing, admission,
 * reasoning steps, held and blank tool results (AMPR-425, AMPR-446), and what a step's
 * request carries. Re-implementing it in either driver would leave those to be kept in
 * step by hand.
 *
 * Routing is strict tool-id dispatch with no keyword fallback. If the step's `toolId` is
 * missing from [SparkBasedAgent.effectiveTools] or matches nothing there, the step fails
 * fast with a clear error. That is the same narrowed set the planner was offered, so a
 * tool the spark stack withdrew is as unreachable as one the agent was never built with
 * (AMPR-400).
 *
 * [Task.CodeChange] and [Task.Step] dispatch identically (AMPR-410). Both carry a
 * description and a nominated tool, and routing a step to a tool does not depend on
 * whether the step changes code. The planner picks which type a step is
 * (`DefaultTaskFactory`), and this reads the pair off either.
 *
 * A step with `toolId == null` nominates no tool because its work is thinking, not acting.
 * The plan_steps schema asks the planner to mark those with `toolToUse = null`. The seat
 * carries it out as one model call, not as a success returned on its behalf (AMPR-407).
 *
 * Every step that nominates a tool is admitted by the seat's [Breaker] first (AMPR-440,
 * F16). A trip returns before the parameter-strategy call and before the engine, with the
 * reason on the step, and an admission that could not be decided fails closed.
 *
 * [runRules] are the rules the *run* puts on every step, beside the ones the tool brings
 * (AMPR-445). A hosted run passes its [RunQuotas] here as Hard quota rules, and the Arc
 * path, which has no per-run policy, passes none. They are admitted before a reasoning
 * step's model call as well as before a tool's, because a reasoning step spends against
 * a run like any other.
 *
 * Suspend-only, like everything in this package. The Arc path's blocking wrapper is the
 * agent's `runLLMToExecuteTask` lambda, outside this function. Nothing here blocks.
 */
internal suspend fun dispatchPlanStep(
    agent: SparkBasedAgent<*>,
    step: Task,
    parentTask: Task,
    priorResults: List<StepOutcome>,
    runRules: List<Pair<Rule, Hardness>> = emptyList(),
): StepResult {
    if (step is Task.Blank) {
        return StepResult.success(
            description = "blank step",
            details = "no-op",
        ).withOrigin(ContentOrigin.Own)
    }
    val (description, toolId) = when (step) {
        is Task.CodeChange -> step.description to step.toolId
        is Task.Step -> step.description to step.toolId
        else -> return StepResult.failure(
            description = step.id,
            error = "Plan step ${step.id} is of unsupported type " +
                "${step::class.simpleName}; spark-based execution only " +
                "handles the Task.CodeChange and Task.Step steps a planner " +
                "emits.",
            isCritical = true,
        )
    }

    if (toolId == null) {
        admit(agent, step, description, tool = null, rules = runRules)?.let { return it }
        return agent.executeReasoningStep(step, description, parentTask, priorResults)
    }

    val dispatchable = agent.effectiveTools
    val tool = dispatchable.firstOrNull { it.id == toolId }
        ?: return StepResult.failure(
            description = description,
            error = "Plan step ${step.id} nominated toolToUse=\"$toolId\", " +
                "which is not in the agent's effective tool set " +
                "(${dispatchable.joinToString { it.id }}) — the tools it was " +
                "built with, narrowed by its spark stack. The executor " +
                "routes strictly by tool id — no keyword fallback.",
            isCritical = true,
        )

    // AMPR-440: the Breaker admits the step before anything is spent on it. A trip
    // returns here, before the parameter-strategy call and before the engine, with the
    // reason on the step; an admission that could not be decided fails closed.
    admit(agent, step, description, tool, Breaker.rulesFor(tool) + runRules)?.let { return it }

    // AMPR-453: what the tool returned is attributed to the tool's declared origin —
    // `External` unless it proved otherwise — success, failure and hold alike, since a
    // failure's error quotes what the tool said.
    val request = agent.buildPlanStepRequest(step, description, parentTask, priorResults)
    val result = when (val outcome = agent.reasoningUnit.executeTool(tool, request)) {
        is ExecutionOutcome.Success -> StepResult.success(
            description = description,
            details = outcome.stepDetails(toolId),
        )
        is ExecutionOutcome.Failure -> StepResult.failure(
            description = description,
            error = "tool=$toolId failed: ${outcome::class.simpleName}; " +
                outcome.describeResult(),
            isCritical = true,
        )
        // Waiting on a person: not done, and not a fault (AMPR-425).
        is ExecutionOutcome.Held -> StepResult.held(
            description = description,
            reason = outcome.reason,
            reference = outcome.reference,
        )
        // The tool ran and had nothing to do: a skipped step, not a done one
        // (AMPR-446). Until then an `else` reported it as a success, so a plan said
        // a step was done that did nothing. No `else` now: an outcome variant added
        // later has to be given a meaning here before this compiles.
        ExecutionOutcome.Blank -> StepResult.skip(
            description = description,
            reason = "tool=$toolId returned nothing to execute",
        )
    }
    return result.withOrigin(tool.resultOrigin)
}

/**
 * The Breaker's answer for [step], as the failure the step ends with, or null when the
 * step may run (AMPR-440, AMPR-445).
 *
 * A trip is the step's failure with the tripped rule's reason; an admission that could
 * not be decided — a grant store that threw, a run record that could not be read — fails
 * closed. Both are critical, so the plan walk stops on them.
 */
private suspend fun admit(
    agent: SparkBasedAgent<*>,
    step: Task,
    description: String,
    tool: Tool<*>?,
    rules: List<Pair<Rule, Hardness>>,
): StepResult? {
    val admission = agent.breaker.admit(tool, rules).getOrElse { e ->
        return StepResult.failure(
            description = description,
            error = "Plan step ${step.id} was not admitted: ${e.message}",
            isCritical = true,
        )
    }
    return (admission as? Admission.Trip)?.let { trip ->
        StepResult.failure(
            description = description,
            error = trip.reason,
            isCritical = true,
        )
    }
}

/**
 * What the step reports having done: the tool it ran, the outcome variant, and what that
 * outcome actually said.
 *
 * The last part is the AMPR-408 half. This used to read `"tool=$toolId outcome=Success"`
 * and nothing more. Even with the results threaded forward, the next step would then have
 * been handed a type name: true, and of no use to a model asked to summarise what the
 * previous step found.
 */
private fun ExecutionOutcome.stepDetails(toolId: String): String {
    val result = describeResult()
    val head = "tool=$toolId outcome=${this::class.simpleName}"
    return if (result.isBlank()) head else "$head; $result"
}
