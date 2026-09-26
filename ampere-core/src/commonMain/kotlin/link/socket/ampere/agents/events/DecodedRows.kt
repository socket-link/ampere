package link.socket.ampere.agents.events

/**
 * A stored row that could not be decoded, named so someone can go look at it (AMPR-364).
 *
 * @property rowId the row's primary key, read from its own column rather than from the payload
 * — available even when nothing in the payload parses.
 * @property reason the decoder's own message, verbatim.
 */
data class UndecodableRow(
    val rowId: String,
    val reason: String,
)

/**
 * What one list-shaped query decoded, plus the rows it had to skip (AMPR-364).
 *
 * A single undecodable row used to fail the whole query: `EventRepository`'s decode threw
 * `EventSerializationException` from inside a `Result.map` block, which does not catch, so the
 * exception escaped the `Result` boundary and one bad row took every good one with it. Now the
 * row is skipped and named here.
 *
 * This *is* the decoded list — it delegates [List] — so every caller that only wants the events
 * reads it unchanged, and the count is there for the ones that care that something was lost. A
 * caller reading a store with no skips cannot tell the difference, which is the point: the
 * degradation is visible without being in the way.
 *
 * [equals] and [hashCode] delegate to the decoded list rather than including [undecodable],
 * because a type that claims to be a `List` and then refuses list equality is a trap. Two
 * results holding the same events compare equal even if one of them skipped a row; the skip is
 * reported on `EventRepository.signals` and on the bus, not through equality.
 */
class DecodedRows<out T>(
    private val decoded: List<T>,
    val undecodable: List<UndecodableRow> = emptyList(),
) : List<T> by decoded {

    /** How many rows this query could not decode. Zero for every store with no skew. */
    val undecodableCount: Int get() = undecodable.size

    override fun equals(other: Any?): Boolean = decoded == other

    override fun hashCode(): Int = decoded.hashCode()

    override fun toString(): String = decoded.toString()
}
