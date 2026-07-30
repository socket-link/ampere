package link.socket.ampere.plug.spi

import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

/**
 * A sink a Plug executes a command through: [C] in, a receipt out.
 *
 * The *operation* half of the chassis boundary paired with [PerceiveSource]
 * — [link.socket.ampere.link.LinkOperation.EXECUTE]. Like [PerceiveSource],
 * `C` is unconstrained rather than bound to
 * [link.socket.ampere.canon.CanonEntity], for the same reason: a Notify send
 * or a Clipboard write is canon-external and still needs a base to
 * implement against. [consumes] is the machine-readable contract; empty
 * means the command carries no canon-typed payload.
 */
interface ExecuteSink<in C> {

    /** The canon types [execute] accepts as part of [C]. Empty when [C] is canon-external. */
    val consumes: Set<CanonType>

    /** Run one command against the native transport. */
    suspend fun execute(command: C): Result<ExecuteReceipt>
}

/**
 * Confirmation that an [ExecuteSink] ran a command.
 *
 * @property linkId The Link the command travelled over — required for the
 *   same consent/provenance reason as [PerceiveQuery.linkId].
 * @property executedAt When the sink completed the command.
 * @property handle The resulting native object, when the transport hands
 *   one back (a created reminder, a sent message). Null when the command
 *   has no addressable result (a pasteboard write, a dismissed
 *   notification).
 */
data class ExecuteReceipt(
    val linkId: LinkId,
    val executedAt: Instant,
    val handle: SourceHandle? = null,
)
