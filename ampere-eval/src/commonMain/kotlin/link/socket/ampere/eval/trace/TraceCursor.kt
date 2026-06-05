package link.socket.ampere.eval.trace

import kotlinx.serialization.Serializable

/**
 * A signal marking where replay stops and a fresh ("branched") execution would
 * take over. The handoff begins at [branchIndex]; [replayed] is the exact prefix
 * of events to replay before that point.
 *
 * Eval is the degenerate case where the handoff never fires: `branchAfter(size - 1)`
 * replays the whole trace and yields a [branchIndex] one past the last event.
 */
@Serializable
data class BranchPoint(
    val replayed: List<TraceEvent>,
    val branchIndex: Int,
)

/**
 * Read-only cursor over a [Trace] for partial replay. Live re-execution and the
 * playback relay are out of scope here (ampere-eval ticket 2).
 */
class TraceCursor(private val trace: Trace) {

    /**
     * Events `0..index` inclusive. The index is coerced into bounds, so this
     * never throws: values `>= size` return the whole trace, and values `< 0`
     * (or any index against an empty trace) return an empty list.
     */
    fun replayTo(index: Int): List<TraceEvent> =
        trace.events.take(coerce(index) + 1)

    /**
     * A [BranchPoint] whose handoff begins at `index + 1`: [BranchPoint.replayed]
     * is events `0..index`, and [BranchPoint.branchIndex] is the coerced
     * `index + 1`. `branchAfter(-1)` branches from the very start;
     * `branchAfter(size - 1)` replays everything.
     */
    fun branchAfter(index: Int): BranchPoint {
        val coerced = coerce(index)
        return BranchPoint(
            replayed = trace.events.take(coerced + 1),
            branchIndex = coerced + 1,
        )
    }

    /** Coerce an index into `-1..size-1` (so `+1` lands in `0..size`); `-1` means "before the first event". */
    private fun coerce(index: Int): Int = index.coerceIn(-1, (trace.size - 1).coerceAtLeast(-1))
}
