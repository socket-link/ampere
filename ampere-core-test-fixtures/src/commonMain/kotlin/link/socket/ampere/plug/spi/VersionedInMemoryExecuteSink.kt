package link.socket.ampere.plug.spi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

/**
 * Reference [ExecuteSink] for a provider with real versioned writes: an
 * in-memory keyed store that bumps a per-record etag on every write and hands
 * back the post-write handle and state on each [ExecuteReceipt].
 *
 * [WritePrecondition.MatchVersion] is checked and applied under one lock, so
 * the check and the write are atomic — the property a sink must have before
 * it may declare a [WritePreconditionKind]. [supportedPreconditions] is a
 * constructor parameter so the same store can stand in for a provider that
 * offers no conditional writes at all.
 */
class VersionedInMemoryExecuteSink(
    private val linkId: LinkId = LinkId("versioned-fixture-link"),
    private val schema: NativeSchema = NativeSchema("VersionedRecord"),
    override val supportedPreconditions: Set<WritePreconditionKind> = setOf(WritePreconditionKind.MATCH_VERSION),
    private val executedAt: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
) : ExecuteSink<VersionedInMemoryExecuteSink.Write> {

    /** Replace the record at [nativeId] with [fields]. */
    data class Write(val nativeId: String, val fields: JsonObject)

    private class Record(val version: Long, val payload: NativePayload)

    private val lock = Mutex()
    private val records = mutableMapOf<String, Record>()

    /** Number of writes that actually reached the store. */
    var writeCount: Int = 0
        private set

    override val consumes: Set<CanonType> = emptySet()

    /** The record's current handle, carrying its current etag; null when absent. */
    suspend fun currentHandle(nativeId: String): SourceHandle? =
        lock.withLock { records[nativeId]?.let { handleFor(nativeId, it) } }

    /** The record's current native state; null when absent. */
    suspend fun currentState(nativeId: String): NativePayload? =
        lock.withLock { records[nativeId]?.payload }

    override suspend fun execute(command: Write): Result<ExecuteReceipt> =
        lock.withLock { Result.success(write(command)) }

    override suspend fun executeIf(command: Write, precondition: WritePrecondition): Result<ExecuteReceipt> {
        if (precondition.kind !in supportedPreconditions) {
            return executeFailure(ExecuteFailure.PreconditionUnsupported(precondition.kind))
        }

        return lock.withLock {
            val current = records[command.nativeId]
            val holds = when (precondition) {
                is WritePrecondition.MatchVersion -> current?.let { etagOf(it) } == precondition.etag
            }

            if (holds) {
                Result.success(write(command))
            } else {
                executeFailure(
                    ExecuteFailure.PreconditionFailed(
                        precondition = precondition,
                        current = current?.let { handleFor(command.nativeId, it) },
                        currentState = current?.payload,
                    ),
                )
            }
        }
    }

    private fun write(command: Write): ExecuteReceipt {
        val record = Record(
            version = (records[command.nativeId]?.version ?: 0L) + 1,
            payload = NativePayload(schema = schema, fields = command.fields),
        )
        records[command.nativeId] = record
        writeCount++
        return ExecuteReceipt(
            linkId = linkId,
            executedAt = executedAt,
            handle = handleFor(command.nativeId, record),
            postWriteState = record.payload,
        )
    }

    private fun etagOf(record: Record): String = "v${record.version}"

    private fun handleFor(nativeId: String, record: Record): SourceHandle =
        SourceHandle(
            linkId = linkId,
            sourceSystem = SOURCE_SYSTEM,
            nativeId = nativeId,
            etag = etagOf(record),
        )

    companion object {
        const val SOURCE_SYSTEM: String = "fixture:versioned-memory"
    }
}
