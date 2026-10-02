package link.socket.ampere.standup

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonId

/**
 * A span of time the person can work in (AMPR-379). The Scheduler fills these in
 * order; where they come from — a calendar plug, a preference, a guess — is the
 * consumer's.
 */
@Serializable
data class AvailabilityWindow(
    val start: Instant,
    val end: Instant,
)

/** One proposed working session on one item. A long item may span several. */
@Serializable
data class ProposedSession(
    val itemId: CanonId,
    val start: Instant,
    val end: Instant,
)
