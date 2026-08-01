package link.socket.ampere.canon.table

import link.socket.ampere.canon.CanonId

/**
 * A write Arc can emit against a `TABLE`, translated natively by the
 * receiving Plug rather than merged by a canon-generic algorithm.
 *
 * This is the AMPR-263 verdict's shape: two intents, never a whole-table
 * replace. The closed membership below is that constraint enforced by the
 * type system — there is no `ReplaceTable` case to add, and a Plug that
 * cannot honor either case losslessly for a given provider must refuse it
 * (see [TableWriteSink]), not accept it and clobber.
 *
 * `AppendRow` never touches an existing cell, so it carries no formula or
 * concurrency hazard by construction (AMPR-263 §2, Model B). `UpdateCell`
 * does, and which providers can accept it — and under what guard — is a
 * per-Plug capability declared in
 * [link.socket.ampere.plug.PlugManifest.tableWriteCapabilities], not
 * something this type can decide.
 */
sealed interface TableWriteIntent {

    /** The `TABLE` canon entity this intent targets. */
    val tableId: CanonId

    /**
     * Add a new row. Values are positional, matching
     * [link.socket.ampere.canon.CanonTable.columnNames] order.
     */
    data class AppendRow(
        override val tableId: CanonId,
        val values: List<String>,
    ) : TableWriteIntent

    /** Overwrite one existing cell. Never a document- or row-level replace. */
    data class UpdateCell(
        override val tableId: CanonId,
        val row: TableRowRef,
        val column: String,
        val value: String,
    ) : TableWriteIntent
}

/** The [TableWriteCapability] a Plug must declare to accept this intent. */
val TableWriteIntent.requiredCapability: TableWriteCapability
    get() = when (this) {
        is TableWriteIntent.AppendRow -> TableWriteCapability.APPEND_ROW
        is TableWriteIntent.UpdateCell -> TableWriteCapability.UPDATE_CELL
    }
