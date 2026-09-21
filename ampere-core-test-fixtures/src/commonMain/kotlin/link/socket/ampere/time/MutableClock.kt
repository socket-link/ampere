package link.socket.ampere.time

import kotlin.time.Duration
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * A [Clock] that only moves when a test moves it.
 *
 * Inject it wherever production code takes a `Clock` (the Arc tick, `Bench`) to make time
 * deterministic: [now] returns [current] until [advance] or [set] changes it.
 */
class MutableClock(start: Instant) : Clock {
    var current: Instant = start
        private set

    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }

    fun set(to: Instant) {
        current = to
    }
}
