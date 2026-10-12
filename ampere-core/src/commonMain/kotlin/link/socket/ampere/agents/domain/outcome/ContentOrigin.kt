package link.socket.ampere.agents.domain.outcome

import kotlinx.serialization.Serializable

/**
 * Where a step result's content came from (AMPR-453, AMPR-385 row H24).
 *
 * When a consumer wrote the plan, text a tool returned could only ever fill a parameter.
 * Once a host seat plans, the same text can steer which tool runs next — so a result
 * says whether its text was produced by the run itself or brought in from outside it,
 * and every prompt that carries it says so too.
 *
 * [External] is the default everywhere, and the direction to be wrong in: an `Own` that
 * should have been `External` lets outside text pass as the run's own words, while the
 * reverse only makes a consumer's write gate more careful than it had to be.
 */
@Serializable
enum class ContentOrigin {

    /**
     * Produced by the run itself, from nothing that came from outside it — a reasoning
     * step's conclusion reached over `Own` inputs only, or Ampere's own control-flow text.
     * Only a tool that can prove this declares it.
     */
    Own,

    /**
     * Brought in from outside the run, or not known not to have been: a web page, a file,
     * a message, an MCP server's answer, anything a tool read. The default.
     */
    External,
}

/**
 * Where this outcome's reported text came from (AMPR-453).
 *
 * The origin a [StepOutcome] carries, or the one `PlanExecutor` stamped on the
 * [ExecutionOutcome.NoChanges] or [ExecutionOutcome.Held] it folded a plan into. Every other
 * outcome carries none and reads [ContentOrigin.External]: a code read, an issue batch or a
 * git response is a tool's own report, and a tool's text is outside the run unless the tool
 * says otherwise — which it does on `Tool.resultOrigin`, not here.
 */
fun Outcome.contentOrigin(): ContentOrigin = when (this) {
    is StepOutcome -> origin
    is ExecutionOutcome.NoChanges.Success -> origin
    is ExecutionOutcome.NoChanges.Failure -> origin
    is ExecutionOutcome.Held -> origin
    else -> ContentOrigin.External
}

/**
 * The origin of text built from text of [origins]: [ContentOrigin.Own] only when every one of
 * them is, else [ContentOrigin.External] — one outside part makes the whole outside. An empty
 * [origins] is `Own`: text built from nothing quotes nothing.
 */
fun originOfAll(origins: Iterable<ContentOrigin>): ContentOrigin =
    if (origins.all { it == ContentOrigin.Own }) ContentOrigin.Own else ContentOrigin.External
