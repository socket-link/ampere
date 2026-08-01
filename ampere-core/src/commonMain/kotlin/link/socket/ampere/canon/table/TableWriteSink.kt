package link.socket.ampere.canon.table

import link.socket.ampere.canon.CanonType
import link.socket.ampere.plug.spi.ExecuteReceipt
import link.socket.ampere.plug.spi.ExecuteSink

/**
 * Guarded [ExecuteSink] for [TableWriteIntent].
 *
 * ## The capability gate is structural, not remembered
 *
 * Mirrors [link.socket.ampere.canon.adapter.WritableCanonAdapter]'s shape:
 * subclasses do not implement [ExecuteSink.execute] directly. Instead
 * [execute] is `final`, checks the intent's [TableWriteIntent.requiredCapability]
 * against [capabilities] before doing anything else, and only then routes to
 * [appendRow] or [updateCell]. A subclass cannot accept an intent it never
 * declared support for without deleting a member of this class — the same
 * guarantee [link.socket.ampere.canon.adapter.WritableCanonAdapter.writeBack]
 * makes for scalar field write-back, applied to the AMPR-263 verdict's
 * per-provider capability gating instead of a per-field `ownedFields` set.
 *
 * [capabilities] is where a provider's AMPR-263 verdict is expressed in code:
 * a CSV sink passes `setOf(APPEND_ROW)` and [updateCell] is never reached: [execute]
 * fails every [TableWriteIntent.UpdateCell] with
 * [TableWriteFailure.CapabilityNotSupported] before dispatch. [updateCell]
 * must still be implemented — Kotlin requires it — but a sink whose
 * [capabilities] omit [TableWriteCapability.UPDATE_CELL] can implement it as
 * an unreachable defensive failure rather than real logic.
 */
abstract class TableWriteSink(
    protected val capabilities: Set<TableWriteCapability>,
) : ExecuteSink<TableWriteIntent> {

    final override val consumes: Set<CanonType> = setOf(CanonType.TABLE)

    final override suspend fun execute(command: TableWriteIntent): Result<ExecuteReceipt> {
        val capability = command.requiredCapability
        if (capability !in capabilities) {
            return tableWriteFailure(
                TableWriteFailure.CapabilityNotSupported(
                    capability = capability,
                    tableId = command.tableId,
                ),
            )
        }

        return when (command) {
            is TableWriteIntent.AppendRow -> appendRow(command)
            is TableWriteIntent.UpdateCell -> updateCell(command)
        }
    }

    /** Runs once [capabilities] confirms [TableWriteCapability.APPEND_ROW] is declared. */
    protected abstract suspend fun appendRow(intent: TableWriteIntent.AppendRow): Result<ExecuteReceipt>

    /** Runs once [capabilities] confirms [TableWriteCapability.UPDATE_CELL] is declared. */
    protected abstract suspend fun updateCell(intent: TableWriteIntent.UpdateCell): Result<ExecuteReceipt>
}
