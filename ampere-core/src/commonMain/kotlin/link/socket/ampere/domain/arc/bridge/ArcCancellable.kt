package link.socket.ampere.domain.arc.bridge

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A handle that stops one observation. The bridge's answer to a `Job`, which does not survive
 * the Objective-C export in a form Swift can hold.
 *
 * [cancel] is idempotent and safe from any thread. It never throws, so a Swift
 * `AsyncStream.onTermination` closure can call it unconditionally.
 */
class ArcCancellable internal constructor(
    private val onCancel: () -> Unit,
) {
    private val cancelled = MutableStateFlow(false)

    /** True once [cancel] has run. Observation has stopped; nothing further will be delivered. */
    val isCancelled: Boolean
        get() = cancelled.value

    /** Stop the observation. Calls after the first are no-ops. */
    fun cancel() {
        if (cancelled.compareAndSet(expect = false, update = true)) {
            onCancel()
        }
    }
}
