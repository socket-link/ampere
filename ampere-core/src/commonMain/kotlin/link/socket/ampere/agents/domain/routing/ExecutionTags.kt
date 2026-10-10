package link.socket.ampere.agents.domain.routing

import link.socket.ampere.agents.domain.task.EffortLevel
import link.socket.ampere.agents.domain.task.ExecutionAssignment

/**
 * The routing tags an [ExecutionAssignment] contributes to a [RoutingContext] (AMPR-413).
 *
 * An assignment says how a unit of work should be run: which model, at what effort.
 * The relay already has a declarative way to act on a fact about a call —
 * [RoutingRule.ByTag] over [RoutingContext.tags] — so an assignment reaches routing as
 * tags rather than as a sixth rule kind, and a consumer that wants one honoured writes
 * `RoutingRule.ByTag(ExecutionTags.model("…"), configuration)` against the same strings
 * this object builds.
 *
 * Why a tag and not a rule that reads the model name off the assignment: a model id
 * there is opaque to Ampere ([ExecutionAssignment.model]) — a provider's id, or a
 * consumer's alias for one — so nothing in the framework can turn it into an
 * `AIConfiguration`. Only the consumer's rule set holds that mapping, and a tag is the
 * handle it needs to express it. A declaration no rule names therefore routes nowhere:
 * the call takes the route it would have taken anyway, and the tag stays on the context
 * where the trace can show that the seat asked.
 */
object ExecutionTags {

    /** Prefix of the tag naming [ExecutionAssignment.model]. */
    const val MODEL_PREFIX: String = "model:"

    /** Prefix of the tag naming [ExecutionAssignment.effort]. */
    const val EFFORT_PREFIX: String = "effort:"

    /** The tag for work that should run on [modelId]. */
    fun model(modelId: String): String = MODEL_PREFIX + modelId

    /** The tag for work that should run at [effort], lower-cased to match its serial name. */
    fun effort(effort: EffortLevel): String = EFFORT_PREFIX + effort.name.lowercase()

    /**
     * Every tag [assignment] contributes: one per property it sets, and none for a
     * property it leaves null. An assignment that sets neither contributes nothing, so
     * tagging a call with it cannot change where that call routes.
     */
    fun of(assignment: ExecutionAssignment): Set<String> = buildSet {
        assignment.model?.let { add(model(it)) }
        assignment.effort?.let { add(effort(it)) }
    }
}
