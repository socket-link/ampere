package link.socket.ampere.agents.domain.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a unit of work should be run, as opposed to who runs it (AMPR-369).
 *
 * This sits alongside [AssignedTo] rather than inside it. `AssignedTo` answers
 * "which agent, team or human"; an `ExecutionAssignment` answers "with which
 * model, at what effort", and the two vary independently — the same agent can
 * be handed one unit of work on a small model and the next on a large one.
 *
 * Both properties are optional. An absent value means the work does not
 * specify one and the runner's own default applies; it does not mean "none".
 *
 * @property model Identifier of the model to run the work with. Opaque to
 *   Ampere: it is carried, not resolved, so it may be a provider model id or a
 *   consumer-defined alias. When the work is routed through Ampere's own
 *   providers it is the value matched against
 *   [link.socket.ampere.agents.domain.routing.capability.ModelDescriptor.modelName].
 * @property effort How much effort the model should spend on the work.
 */
@Serializable
data class ExecutionAssignment(
    val model: String? = null,
    val effort: EffortLevel? = null,
)

/**
 * How much effort a model should spend on a unit of work.
 *
 * A closed, provider-neutral set. Providers name and grade their effort or
 * reasoning controls differently, so mapping a level onto a provider parameter
 * belongs to whatever runs the work, not to the unit of work.
 */
@Serializable
enum class EffortLevel {

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
}
