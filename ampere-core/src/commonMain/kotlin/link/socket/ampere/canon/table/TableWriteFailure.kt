package link.socket.ampere.canon.table

import link.socket.ampere.canon.CanonId

/**
 * Why a [TableWriteIntent] could not be executed.
 *
 * The list is closed; callers can rely on `when` being exhaustive. Mirrors
 * [link.socket.ampere.canon.adapter.CanonConversionFailure]'s shape for the
 * same reason: a typed, closed failure set is what lets a caller (or a
 * future Arc retry policy) branch on *why*, not just that a write failed.
 */
sealed interface TableWriteFailure {

    /**
     * The intent named a [TableWriteCapability] the receiving sink did not
     * declare. This is the guard that keeps the AMPR-263 non-negotiable
     * honest: a sink that cannot honor preserve-and-merge for a capability
     * simply never declares it, and every intent of that shape fails here
     * before any native write is attempted.
     */
    data class CapabilityNotSupported(
        val capability: TableWriteCapability,
        val tableId: CanonId,
    ) : TableWriteFailure

    /**
     * The target cell holds a provider-native formula, and canon carries
     * values, not formulas (AMPR-263 §2, the formula-cell hazard). Writing
     * through would silently replace the formula with a literal.
     */
    data class FormulaCellWrite(
        val tableId: CanonId,
        val row: TableRowRef,
        val column: String,
    ) : TableWriteFailure

    /**
     * The target column is a provider-computed property (e.g. a Notion
     * `formula`/`rollup` property) rather than a stored value. Distinct from
     * [FormulaCellWrite]: this is a schema-level fact about the column, not
     * a per-cell one.
     */
    data class ComputedColumnWrite(
        val tableId: CanonId,
        val column: String,
    ) : TableWriteFailure

    /**
     * The write was rejected because the native table changed between read
     * and write. Whether this is detected precisely (a per-row etag) or
     * coarsely (a whole-document revision) is a provider fact, not something
     * this failure encodes — see the AMPR-263 provider survey.
     */
    data class ConcurrentModification(
        val tableId: CanonId,
        val reason: String,
    ) : TableWriteFailure

    /** [TableRowRef] did not resolve to a row in the native table. */
    data class RowNotFound(
        val tableId: CanonId,
        val row: TableRowRef,
    ) : TableWriteFailure

    /** The transport rejected the write for a reason none of the above name. */
    data class WriteRejected(
        val tableId: CanonId,
        val reason: String,
    ) : TableWriteFailure
}

/**
 * Carries a [TableWriteFailure] through [Result.failure], the same role
 * [link.socket.ampere.canon.adapter.CanonConversionException] plays for
 * [link.socket.ampere.canon.adapter.CanonConversionFailure].
 */
class TableWriteException(
    val failure: TableWriteFailure,
) : Exception("Table write failed: $failure")

/** Shorthand for the Result-typed failure path. */
fun <T> tableWriteFailure(failure: TableWriteFailure): Result<T> =
    Result.failure(TableWriteException(failure))
