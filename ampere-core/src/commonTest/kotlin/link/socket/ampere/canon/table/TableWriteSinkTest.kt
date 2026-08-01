package link.socket.ampere.canon.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonType
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.ExecuteReceipt

class TableWriteSinkTest {

    private val linkId = LinkId("csv-mount-1")
    private val executedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val tableId = CanonId("table-1")

    private class RecordingSink(
        capabilities: Set<TableWriteCapability>,
        private val linkId: LinkId,
        private val executedAt: Instant,
    ) : TableWriteSink(capabilities) {
        var appendCalls = 0
        var updateCalls = 0

        override suspend fun appendRow(intent: TableWriteIntent.AppendRow): Result<ExecuteReceipt> {
            appendCalls++
            return Result.success(ExecuteReceipt(linkId = linkId, executedAt = executedAt))
        }

        override suspend fun updateCell(intent: TableWriteIntent.UpdateCell): Result<ExecuteReceipt> {
            updateCalls++
            return Result.success(ExecuteReceipt(linkId = linkId, executedAt = executedAt))
        }
    }

    @Test
    fun `a sink always declares TABLE as its consumed canon type`() {
        val sink = RecordingSink(setOf(TableWriteCapability.APPEND_ROW), linkId, executedAt)

        assertEquals(setOf(CanonType.TABLE), sink.consumes)
    }

    @Test
    fun `append-row runs when declared`() = runTest {
        val sink = RecordingSink(setOf(TableWriteCapability.APPEND_ROW), linkId, executedAt)

        val result = sink.execute(TableWriteIntent.AppendRow(tableId, listOf("a", "b")))

        assertTrue(result.isSuccess)
        assertEquals(1, sink.appendCalls)
        assertEquals(0, sink.updateCalls)
    }

    @Test
    fun `update-cell is refused before dispatch when not declared`() = runTest {
        // This is the CSV leg of the AMPR-263 verdict: a sink that never
        // declares UPDATE_CELL must never reach updateCell(), because that
        // is the only way preserve-and-merge stays honest for a provider
        // that can't honor it.
        val sink = RecordingSink(setOf(TableWriteCapability.APPEND_ROW), linkId, executedAt)

        val result = sink.execute(
            TableWriteIntent.UpdateCell(tableId, TableRowRef.Position(0), "status", "Done"),
        )

        assertTrue(result.isFailure)
        assertEquals(0, sink.updateCalls)

        val failure = assertIs<TableWriteException>(result.exceptionOrNull()).failure
        val capabilityFailure = assertIs<TableWriteFailure.CapabilityNotSupported>(failure)
        assertEquals(TableWriteCapability.UPDATE_CELL, capabilityFailure.capability)
        assertEquals(tableId, capabilityFailure.tableId)
    }

    @Test
    fun `update-cell runs when declared`() = runTest {
        val sink = RecordingSink(
            setOf(TableWriteCapability.APPEND_ROW, TableWriteCapability.UPDATE_CELL),
            linkId,
            executedAt,
        )

        val result = sink.execute(
            TableWriteIntent.UpdateCell(tableId, TableRowRef.NativeRowId("page-1"), "status", "Done"),
        )

        assertTrue(result.isSuccess)
        assertEquals(1, sink.updateCalls)
    }

    @Test
    fun `append-row is refused before dispatch when the sink only supports update-cell`() = runTest {
        val sink = RecordingSink(setOf(TableWriteCapability.UPDATE_CELL), linkId, executedAt)

        val result = sink.execute(TableWriteIntent.AppendRow(tableId, listOf("a")))

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertEquals(0, sink.appendCalls)
    }

    @Test
    fun `requiredCapability maps each intent to the capability that must be declared`() {
        assertEquals(
            TableWriteCapability.APPEND_ROW,
            TableWriteIntent.AppendRow(tableId, emptyList()).requiredCapability,
        )
        assertEquals(
            TableWriteCapability.UPDATE_CELL,
            TableWriteIntent.UpdateCell(tableId, TableRowRef.Position(0), "col", "v").requiredCapability,
        )
    }
}
