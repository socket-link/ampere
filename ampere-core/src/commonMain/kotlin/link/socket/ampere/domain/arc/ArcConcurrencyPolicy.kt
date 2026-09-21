package link.socket.ampere.domain.arc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What an Arc does when a new run is requested while one is already in flight (AMPR-284).
 *
 * The enum describes the design space; [ArcConfig.concurrency]'s default describes today. Only
 * [REJECT] is implemented. The others are declared so that a later change of policy is a
 * config change with a name, rather than the discovery of a new behaviour — and an
 * [AmpereRuntime] refuses to be built for any of them rather than silently falling back to
 * [REJECT].
 */
@Serializable
enum class ArcConcurrencyPolicy {
    /** Refuse the new run with an [ArcRunRejectedException]. The in-flight run is untouched. */
    @SerialName("reject")
    REJECT,

    /** Cancel the in-flight run and start the new one. Declared, not implemented. */
    @SerialName("supersede")
    SUPERSEDE,

    /** Hold the new run until the in-flight one is terminal. Declared, not implemented. */
    @SerialName("queue")
    QUEUE,

    /** Run the new run alongside the in-flight one. Declared, not implemented. */
    @SerialName("parallel")
    PARALLEL,
    ;

    /** Whether [AmpereRuntime] can honour this policy. */
    val isImplemented: Boolean
        get() = this == REJECT
}

/**
 * A run was refused because the runtime already has one in flight and its Arc declares
 * [ArcConcurrencyPolicy.REJECT].
 *
 * A typed refusal, so a caller can tell "try again later" apart from a genuine failure. Extends
 * [IllegalStateException] so callers written against the old untyped guard still catch it.
 *
 * @property arcName The Arc whose runtime refused the run.
 * @property policy The declared policy that produced the refusal.
 */
class ArcRunRejectedException(
    val arcName: String,
    val policy: ArcConcurrencyPolicy,
) : IllegalStateException(
    "Runtime is already executing; arc '$arcName' declares concurrency policy $policy",
)
