package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ConditionalExecuteTest {

    private val nativeId = "issue-1"

    private fun fields(assignee: String) = JsonObject(mapOf("assignee" to JsonPrimitive(assignee)))

    private fun failureOf(result: Result<ExecuteReceipt>): ExecuteFailure =
        assertIs<ExecuteException>(result.exceptionOrNull()).failure

    @Test
    fun `a receipt carries the post-write etag and state`() = runTest {
        val sink = VersionedInMemoryExecuteSink()

        val receipt = sink.execute(VersionedInMemoryExecuteSink.Write(nativeId, fields("a"))).getOrThrow()

        assertEquals("v1", receipt.handle?.etag)
        assertEquals(fields("a"), receipt.postWriteState?.fields)
    }

    @Test
    fun `a matching version precondition is honored and returns the new version`() = runTest {
        val sink = VersionedInMemoryExecuteSink()
        val read = sink.execute(VersionedInMemoryExecuteSink.Write(nativeId, fields("a"))).getOrThrow()

        val receipt = sink.executeIf(
            VersionedInMemoryExecuteSink.Write(nativeId, fields("b")),
            WritePrecondition.MatchVersion(read.handle!!.etag!!),
        ).getOrThrow()

        assertEquals("v2", receipt.handle?.etag)
        assertEquals(fields("b"), receipt.postWriteState?.fields)
        assertEquals(fields("b"), sink.currentState(nativeId)?.fields)
        assertEquals(2, sink.writeCount)
    }

    @Test
    fun `a stale version precondition fails typed and writes nothing`() = runTest {
        val sink = VersionedInMemoryExecuteSink()
        val read = sink.execute(VersionedInMemoryExecuteSink.Write(nativeId, fields("a"))).getOrThrow()
        // Another writer lands between our read and our conditional write.
        sink.execute(VersionedInMemoryExecuteSink.Write(nativeId, fields("rival"))).getOrThrow()

        val precondition = WritePrecondition.MatchVersion(read.handle!!.etag!!)
        val result = sink.executeIf(VersionedInMemoryExecuteSink.Write(nativeId, fields("b")), precondition)

        val failed = assertIs<ExecuteFailure.PreconditionFailed>(failureOf(result))
        assertEquals(precondition, failed.precondition)
        // The rejection carries who won, so the caller needs no Perceive round-trip.
        assertEquals("v2", failed.current?.etag)
        assertEquals(fields("rival"), failed.currentState?.fields)
        assertEquals(fields("rival"), sink.currentState(nativeId)?.fields)
        assertEquals(2, sink.writeCount)
    }

    @Test
    fun `a version precondition against a missing record fails with no current state`() = runTest {
        val sink = VersionedInMemoryExecuteSink()

        val result = sink.executeIf(
            VersionedInMemoryExecuteSink.Write(nativeId, fields("a")),
            WritePrecondition.MatchVersion("v1"),
        )

        val failed = assertIs<ExecuteFailure.PreconditionFailed>(failureOf(result))
        assertNull(failed.current)
        assertNull(failed.currentState)
        assertEquals(0, sink.writeCount)
    }

    @Test
    fun `a sink without the capability refuses loudly and never downgrades to an unconditional write`() = runTest {
        val sink = VersionedInMemoryExecuteSink(supportedPreconditions = emptySet())
        val read = sink.execute(VersionedInMemoryExecuteSink.Write(nativeId, fields("a"))).getOrThrow()

        val result = sink.executeIf(
            VersionedInMemoryExecuteSink.Write(nativeId, fields("b")),
            WritePrecondition.MatchVersion(read.handle!!.etag!!),
        )

        val unsupported = assertIs<ExecuteFailure.PreconditionUnsupported>(failureOf(result))
        assertEquals(WritePreconditionKind.MATCH_VERSION, unsupported.kind)
        assertEquals(fields("a"), sink.currentState(nativeId)?.fields)
        assertEquals(1, sink.writeCount)
    }
}

class VersionedInMemoryExecuteSinkContractTest :
    ExecuteSinkPreconditionContract<VersionedInMemoryExecuteSink.Write>() {

    override fun sink(): ExecuteSink<VersionedInMemoryExecuteSink.Write> = VersionedInMemoryExecuteSink()

    override fun command() = VersionedInMemoryExecuteSink.Write("contract-id", JsonObject(emptyMap()))
}

class UndeclaredVersionedInMemoryExecuteSinkContractTest :
    ExecuteSinkPreconditionContract<VersionedInMemoryExecuteSink.Write>() {

    override fun sink(): ExecuteSink<VersionedInMemoryExecuteSink.Write> =
        VersionedInMemoryExecuteSink(supportedPreconditions = emptySet())

    override fun command() = VersionedInMemoryExecuteSink.Write("contract-id", JsonObject(emptyMap()))
}
