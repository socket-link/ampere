package link.socket.ampere.canon.table

/**
 * Identifies the target row of a [TableWriteIntent.UpdateCell].
 *
 * `TABLE` has no addressable row identity today — [link.socket.ampere.canon.CanonTablePreview]
 * is a truncated, positional window, not a keyed row set (AMPR-263 recon). The
 * two cases here are the two identity primitives a provider can actually
 * offer, per the AMPR-263 provider survey:
 *
 *  - [Position] — a row's index in the table, the only identity Google Sheets
 *    and a folder-mounted CSV can offer. It is fragile: inserting or removing
 *    a row above it shifts every index below, so it is only trustworthy
 *    immediately after the read that produced it.
 *  - [NativeRowId] — a stable, provider-native identifier that survives
 *    reordering, e.g. a Notion database row's `page_id`. Sinks that can
 *    accept this case should prefer it.
 *
 * Which case a given [link.socket.ampere.plug.spi.ExecuteSink] accepts is a
 * per-provider capability, not something this type enforces structurally.
 */
sealed interface TableRowRef {

    data class Position(val index: Int) : TableRowRef

    data class NativeRowId(val id: String) : TableRowRef
}
