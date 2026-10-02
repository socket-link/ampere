package link.socket.ampere.roster

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Identity of a role on a roster (AMPR-379).
 *
 * A role is a seat, not an agent: the Planner is still the Planner whichever agent
 * instance fills it this run. The value doubles as the role's `MessageSender.Agent`
 * id in a Room, which is why it is a short word the user can read (`planner`,
 * `scout`) rather than a UUID — "the individual words the user sees are the role
 * names".
 */
@JvmInline
@Serializable
value class RoleId(val value: String)
