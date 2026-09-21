package link.socket.ampere.plug.spi

import kotlinx.serialization.Serializable

/**
 * A condition the *provider* must check atomically, at write time, before an
 * [ExecuteSink.executeIf] command takes effect.
 *
 * There is deliberately no `None` case: "no precondition" is
 * [ExecuteSink.execute]. Keeping the unconditional path on a separate member
 * is what stops a sink from treating a precondition as optional — see
 * [ExecuteSink.executeIf].
 *
 * The closed set here is what a caller can *ask* for. What a given sink can
 * *honour* is declared per sink in [ExecuteSink.supportedPreconditions].
 */
sealed interface WritePrecondition {

    /** The capability a sink must declare to honour this precondition. */
    val kind: WritePreconditionKind

    /**
     * Write only if the target's current provider version still equals
     * [etag] — if-match / compare-and-swap semantics.
     *
     * [etag] is the opaque token a sink hands out in
     * [link.socket.ampere.canon.SourceHandle.etag], either on a perceived
     * entity's provenance or on a prior [ExecuteReceipt.handle].
     */
    data class MatchVersion(val etag: String) : WritePrecondition {
        override val kind: WritePreconditionKind get() = WritePreconditionKind.MATCH_VERSION
    }
}

/**
 * A [WritePrecondition] subtype an [ExecuteSink] positively declares its
 * provider enforces atomically, per [ExecuteSink.supportedPreconditions].
 *
 * Declaring a kind is a claim about the *provider*, not the sink: a sink that
 * emulates a precondition client-side (read, compare, write) has a race
 * between the compare and the write, and must not declare the kind.
 */
@Serializable
enum class WritePreconditionKind {
    MATCH_VERSION,
}
