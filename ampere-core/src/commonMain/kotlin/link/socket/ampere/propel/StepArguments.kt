package link.socket.ampere.propel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.DispatchArguments
import link.socket.ampere.agents.execution.PreDispatchCheck
import link.socket.ampere.agents.execution.PreDispatchVerdict
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.AmpereStableApi
import okio.ByteString.Companion.encodeUtf8

/**
 * A plan step's arguments as a value a gate can compare (AMPR-454, AMPR-385 row H25).
 *
 * A consumer that shows a write's fields before it runs needs two things from the plan it
 * reviews: the fields, which every [Task.Step] carries as
 * [arguments][Task.Step.arguments], and a way to tell later whether they are still the
 * fields it approved. [hash] is the second. It is computed over [canonical] JSON — every
 * object's keys sorted, no whitespace — so two argument values that differ only in key
 * order hash the same, and any difference in a value hashes differently.
 *
 * A step with no arguments, a JSON `null`, and an empty object all canonicalise to `{}`:
 * none of them carries a value, so none of them can hide a value the gate did not see. A
 * tool whose schema declares no arguments is dispatched with an empty object for a step
 * that stated none, and that is not a change.
 *
 * Array order is kept, because it is part of the value. Numbers are compared as written,
 * so `1` and `1.0` are different arguments — they are different text on the wire.
 */
@AmpereStableApi
object StepArguments {

    /** [arguments] as canonical JSON text: keys sorted at every depth, no whitespace. */
    fun canonical(arguments: JsonElement?): String = when {
        arguments == null || arguments is JsonNull -> EMPTY
        else -> sorted(arguments).toString()
    }

    /** The SHA-256 hex digest of [canonical]. Stable across processes and platforms. */
    fun hash(arguments: JsonElement?): String = canonical(arguments).encodeUtf8().sha256().hex()

    /** Whether [a] and [b] are the same arguments once canonicalised. */
    fun same(a: JsonElement?, b: JsonElement?): Boolean = canonical(a) == canonical(b)

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to sorted(value) },
        )
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }

    private const val EMPTY = "{}"
}

/**
 * The hash of this step's [arguments][Task.Step.arguments] — [StepArguments.hash].
 *
 * What a [PlanGate] keeps beside an approval to tell, when
 * [PlanGate.reviewChangedStep] is called, which fields moved since it said yes.
 */
@AmpereStableApi
val Task.Step.argumentsHash: String
    get() = StepArguments.hash(arguments)

/**
 * What the gate said when a step was about to run with arguments it had not approved.
 * Null on [StepArgumentGate.decision] means it was never asked.
 */
internal sealed interface Regated {

    /** The gate approved [arguments], which are what the step ran with. */
    data class Approved(val arguments: JsonElement) : Regated

    /** The gate approved a plan without the step: it is skipped, and the run goes on. */
    data object Removed : Regated

    /** The gate stopped the run: the step does not run, and neither does anything after it. */
    data class Stopped(val decision: PlanDecision.Stopped) : Regated
}

/**
 * The pre-dispatch check one hosted step runs under (AMPR-454).
 *
 * [RosterHostedRun] installs one around each step's dispatch, holding the step as the
 * [PlanGate] approved it. `ToolExecutionEngine` hands it the arguments the tool is about
 * to receive — after any parameter strategy, after the dispatch funnel — and this decides
 * whether they may go:
 *
 * - the same arguments the gate approved: dispatch, and the gate is not asked again;
 * - different arguments, or arguments the engine could not show: ask
 *   [PlanGate.reviewChangedStep], and dispatch only on an approval that keeps the step
 *   with exactly those arguments;
 * - a tool other than the one the step nominates: refuse. Nothing in a hosted step should
 *   reach the engine with another tool, and a gate was never shown one.
 *
 * Every refusal is a failure the engine returns without dispatching, so a step that
 * somehow escaped [RosterHostedRun]'s reading of [decision] still fails rather than
 * passes. The run reads [decision] after the step returns, to record a removal as Skipped
 * and a stop as the run's.
 */
internal class StepArgumentGate(
    private val step: Task.Step,
    private val planGate: PlanGate,
) : PreDispatchCheck() {

    /** What the gate said when asked again. Null when the arguments never changed. */
    var decision: Regated? = null
        private set

    override suspend fun review(tool: Tool<*>, call: DispatchArguments): PreDispatchVerdict {
        if (tool.id != step.toolId) {
            return PreDispatchVerdict.Refuse(
                "tool '${tool.id}' is not the tool the plan gate approved for step '${step.id}' " +
                    "(${step.toolId ?: "no tool"})",
            )
        }
        if (call.visible && StepArguments.same(step.arguments, call.arguments)) {
            return PreDispatchVerdict.Proceed(reviewed = false)
        }

        val decided = planGate.reviewChangedStep(
            step = step,
            approved = step.arguments,
            actual = call.arguments,
        )
        return when (decided) {
            is PlanDecision.Stopped -> {
                decision = Regated.Stopped(decided)
                PreDispatchVerdict.Refuse("stopped at the plan gate: ${decided.reason}")
            }
            is PlanDecision.Approved -> {
                val kept = decided.plan.tasks.firstOrNull { it.id == step.id }
                when {
                    kept == null -> {
                        decision = Regated.Removed
                        PreDispatchVerdict.Refuse(REMOVED)
                    }
                    kept is Task.Step && StepArguments.same(kept.arguments, call.arguments) -> {
                        decision = Regated.Approved(call.arguments)
                        PreDispatchVerdict.Proceed(reviewed = true)
                    }
                    // A gate that rewrites the arguments has approved a call nothing would
                    // make: the step runs with what its strategy produced or not at all.
                    else -> PreDispatchVerdict.Refuse(
                        "the plan gate approved step '${step.id}' with arguments other than " +
                            "the ones it would run with, so it did not run",
                    )
                }
            }
        }
    }

    companion object {

        const val REMOVED: String = "removed at the plan gate once its arguments changed"

        /**
         * The check for [step], or null for a kind of task that never reaches a tool —
         * `executePlanStep` dispatches only a [Task.Step] or a [Task.CodeChange].
         *
         * A [Task.CodeChange] carries no arguments, so the gate approved it with none and
         * is asked again about whatever its strategy produces, shown as the
         * [Task.Step] it would be.
         */
        fun forStep(step: Task, planGate: PlanGate): StepArgumentGate? = when (step) {
            is Task.Step -> StepArgumentGate(step, planGate)
            is Task.CodeChange -> StepArgumentGate(
                Task.Step(
                    id = step.id,
                    status = step.status,
                    description = step.description,
                    toolId = step.toolId,
                    assignedTo = step.assignedTo,
                ),
                planGate,
            )
            else -> null
        }
    }
}

/** [plan] with step [stepId] now carrying [arguments], so `HostedRun.plan` shows what ran. */
internal fun Plan.withStepArguments(stepId: String, arguments: JsonElement): Plan = when (this) {
    is Plan.ForTask -> copy(
        tasks = tasks.map { task ->
            if (task.id == stepId && task is Task.Step) task.copy(arguments = arguments) else task
        },
    )
    else -> this
}
