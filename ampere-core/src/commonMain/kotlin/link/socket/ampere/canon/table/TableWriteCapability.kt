package link.socket.ampere.canon.table

import kotlinx.serialization.Serializable

/**
 * A [TableWriteIntent] subtype a Plug positively declares it can honor
 * losslessly, per [link.socket.ampere.plug.PlugManifest.tableWriteCapabilities].
 *
 * There is no `REPLACE_TABLE` member and there never will be — the AMPR-263
 * verdict forbids offering whole-table replace on any provider, so the
 * closed membership of this enum *is* that constraint, not just a
 * documentation note about it.
 *
 * Per the AMPR-263 provider survey, [APPEND_ROW] is honorable losslessly by
 * every surveyed provider (Sheets `values.append`, Notion `pages.create`, a
 * CSV append), while [UPDATE_CELL] is not — a Plug that cannot honor
 * preserve-and-merge for a given provider's existing cells must omit
 * [UPDATE_CELL] from its declared capabilities rather than accept the intent
 * and weaken the guarantee.
 */
@Serializable
enum class TableWriteCapability {
    APPEND_ROW,
    UPDATE_CELL,
}
