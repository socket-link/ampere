package link.socket.ampere.plug.spi

import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativePayload
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
 *
 * ## Conditional writes refuse loudly by default
 *
 * [execute] is unconditional. A caller that needs "write only if the target
 * has not moved" uses [executeIf] instead, after checking
 * [supportedPreconditions]. [executeIf]'s default implementation refuses
 * every precondition with [ExecuteFailure.PreconditionUnsupported], so a sink
 * that never opted in can never silently downgrade a conditional write to an
 * unconditional one — the only way to do that is to override [executeIf] and
 * deliberately ignore the precondition.
 *
 * For a provider without atomic conditional writes, arbitrate at the protocol
 * level instead (e.g. claim-by-write-then-verify); preconditions do not
 * replace that pattern, they make it unnecessary only where the provider can
 * enforce the condition itself.
 */
interface ExecuteSink<in C> {

    /** The canon types [execute] accepts as part of [C]. Empty when [C] is canon-external. */
    val consumes: Set<CanonType>

    /**
     * The [WritePreconditionKind]s this sink's provider enforces atomically.
     * Empty — the default — means unconditional writes only, and [executeIf]
     * refuses everything.
     *
     * Declare a kind only when the provider itself rejects the write on a
     * mismatch; a client-side read-compare-write races and must not be
     * declared.
     */
    val supportedPreconditions: Set<WritePreconditionKind> get() = emptySet()

    /** Run one command against the native transport, unconditionally. */
    suspend fun execute(command: C): Result<ExecuteReceipt>

    /**
     * Run one command only if [precondition] holds at write time.
     *
     * The default refuses loudly with [ExecuteFailure.PreconditionUnsupported].
     * An override must refuse the same way for any kind outside
     * [supportedPreconditions] before touching the transport, and report a
     * violated precondition as [ExecuteFailure.PreconditionFailed].
     */
    suspend fun executeIf(command: C, precondition: WritePrecondition): Result<ExecuteReceipt> =
        executeFailure(ExecuteFailure.PreconditionUnsupported(precondition.kind))
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
 *   notification). Its [SourceHandle.etag] is the post-write provider
 *   version when the provider returns one — the token a follow-up
 *   [WritePrecondition.MatchVersion] matches against.
 * @property postWriteState The native object as the provider reports it
 *   after the write, when it returns one. Lets a caller confirm the write
 *   landed as intended without a Perceive round-trip.
 */
data class ExecuteReceipt(
    val linkId: LinkId,
    val executedAt: Instant,
    val handle: SourceHandle? = null,
    val postWriteState: NativePayload? = null,
)
