package link.socket.ampere.plug.spi

import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.SourceHandle

/**
 * Why an [ExecuteSink] command could not be executed, for the failures the
 * SPI itself defines.
 *
 * The list is closed; callers can rely on `when` being exhaustive. Mirrors
 * [link.socket.ampere.canon.table.TableWriteFailure]'s shape. The two cases
 * are deliberately distinct because a caller's response to each differs:
 * [PreconditionUnsupported] means fall back to protocol-level arbitration;
 * [PreconditionFailed] means the race was lost and the caller should re-plan.
 */
sealed interface ExecuteFailure {

    /**
     * The sink does not declare [kind] in
     * [ExecuteSink.supportedPreconditions]. No native write was attempted —
     * the command was refused, never downgraded to an unconditional write.
     */
    data class PreconditionUnsupported(
        val kind: WritePreconditionKind,
    ) : ExecuteFailure

    /**
     * The provider checked [precondition] and it did not hold; nothing was
     * written.
     *
     * @property current The target's current handle — and so its current
     *   [SourceHandle.etag] — when the provider's rejection carries it.
     * @property currentState The target's current native state when the
     *   provider's rejection carries it. Either lets a caller re-plan without
     *   a Perceive round-trip.
     */
    data class PreconditionFailed(
        val precondition: WritePrecondition,
        val current: SourceHandle? = null,
        val currentState: NativePayload? = null,
    ) : ExecuteFailure
}

/**
 * Carries an [ExecuteFailure] through [Result.failure], the same role
 * [link.socket.ampere.canon.table.TableWriteException] plays for
 * [link.socket.ampere.canon.table.TableWriteFailure].
 */
class ExecuteException(
    val failure: ExecuteFailure,
) : Exception("Execute failed: $failure")

/** Shorthand for the Result-typed failure path. */
fun <T> executeFailure(failure: ExecuteFailure): Result<T> =
    Result.failure(ExecuteException(failure))
