package link.socket.ampere.agents.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventStoreFailure
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.utils.EventSerializationException
import link.socket.ampere.data.Repository
import link.socket.ampere.db.Database
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.db.events.EventStorePruning
import link.socket.ampere.db.events.EventStorePruningQueries
import link.socket.ampere.db.events.EventStoreQueries
import link.socket.ampere.util.ioDispatcher
import link.socket.ampere.util.truncateStringLeaves

/**
 * Repository responsible for persisting and querying Events using SQLDelight.
 *
 * This lives in common code and works across KMP targets. Callers are responsible for
 * providing a platform-specific SQLDelight `SqlDriver` to construct the generated [Database]
 * instance and then pass it into this repository.
 *
 * Writes are bounded (AMPR-301). [saveEvent] enforces [EventStoreBudget] at the single
 * chokepoint every persisted event passes through, and reports what it had to do on [signals].
 *
 * Reads degrade rather than failing (AMPR-364). A row this build cannot decode — a payload
 * naming an [Event] subtype it does not have, which version skew alone produces — is skipped by
 * the list-shaped queries and counted on the [DecodedRows] they return, and announced on
 * [signals] as [EventStoreSignal.RowUndecodable]. Single-row reads return the failure through
 * their [Result], where [EventStoreFailure.classify] reads it as
 * [EventStoreFailure.SERIALIZATION]; nothing throws through a `Result.map` any more.
 *
 * @param maxEventBytes per-event budget; see [EventStoreBudget.MAX_EVENT_BYTES]. Overridable for tests.
 * @param maxStoreBytes whole-store budget; see [EventStoreBudget.MAX_STORE_BYTES]. Overridable for tests.
 * @param maxStringFieldChars truncation granularity; see [EventStoreBudget.MAX_STRING_FIELD_CHARS].
 * @param pruneToBytes low-water mark a pruning pass rotates down to; see [EventStoreBudget.PRUNE_TO_BYTES].
 * @param sweepIntervalBytes bytes written between budget sweeps; see [EventStoreBudget.SWEEP_INTERVAL_BYTES].
 */
// TODO: Remove duplication
class EventRepository(
    override val json: Json,
    override val scope: CoroutineScope,
    private val database: Database,
    private val maxEventBytes: Int = EventStoreBudget.MAX_EVENT_BYTES,
    private val maxStoreBytes: Long = EventStoreBudget.MAX_STORE_BYTES,
    private val maxStringFieldChars: Int = EventStoreBudget.MAX_STRING_FIELD_CHARS,
    private val pruneToBytes: Long = EventStoreBudget.PRUNE_TO_BYTES,
    private val sweepIntervalBytes: Long = EventStoreBudget.SWEEP_INTERVAL_BYTES,
) : Repository<EventId, Event>(json, scope) {

    override val tag: String = "Event${super.tag}"

    private val queries: EventStoreQueries
        get() = database.eventStoreQueries

    private val pruningQueries: EventStorePruningQueries
        get() = database.eventStorePruningQueries

    private val _signals = MutableSharedFlow<EventStoreSignal>(
        replay = 0,
        extraBufferCapacity = SIGNAL_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * What the budget did to the record, as it happens (AMPR-301).
     *
     * A write refused, a payload cut, rows rotated out — each one a place where the store and
     * what actually happened have come apart. Hot: emissions never suspend and are dropped
     * rather than buffered without limit, so a consumer that stops collecting slows nothing
     * down and, in a ticket about unbounded growth, adds no unbounded queue of its own.
     */
    val signals: SharedFlow<EventStoreSignal> = _signals.asSharedFlow()

    /**
     * Bytes written since the last budget sweep, and the reason the first save of every
     * repository sweeps: it starts at the interval, so a process that opens an
     * already-oversized database reclaims on its first write rather than after 4 MiB more.
     *
     * Read and written only under [sequenceLock].
     */
    private var bytesSinceSweep: Long = sweepIntervalBytes

    /**
     * Persist the given [event] by serializing it to JSON and inserting it into `EventStore`
     * inside its [envelope].
     *
     * The `sequence` is read and assigned inside one transaction with the insert, under
     * [sequenceLock], so it is unique and monotonic even when publishers race (a bus handler
     * publishing while the agent that triggered it publishes its next event). [recordedAt] is
     * what the door's clock
     * said at publish; it defaults to the event's own timestamp for direct repository saves,
     * which mirrors how the migration backfilled legacy rows.
     *
     * `run_id` is exactly `envelope.runId` (F4). The publisher is the only party that knows
     * which Arc run an event belongs to, so nothing here infers it from the event's fields;
     * a null envelope run id is stored as NULL.
     *
     * [EventStoreBudget] is enforced here, on the way in (AMPR-301). A payload over
     * [maxEventBytes] has its oversized string leaves cut and the row is flagged `truncated`;
     * once [maxStoreBytes] of payload has accumulated the oldest rows are rotated out down to
     * [pruneToBytes] and the pass is recorded in `EventStorePruning`. Both leave a marker, and
     * both are announced on [signals] — nothing is dropped or shortened silently. A failed
     * write is announced there too, since the caller's [Result] is the only other thing that
     * knows about it.
     */
    suspend fun saveEvent(
        event: Event,
        envelope: EventEnvelope = EventEnvelope(),
        recordedAt: Instant = event.timestamp,
    ): Result<StoredEvent> =
        withContext(ioDispatcher) {
            runCatching {
                val encoded = encodeWithinBudget(event)
                val runId = envelope.runId

                sequenceLock.withLock {
                    val stored = database.transactionWithResult {
                        val sequence = queries.nextSequence().executeAsOne()
                        queries.insertEvent(
                            event_id = event.eventId,
                            event_type = event.eventType,
                            source_id = event.eventSource.getIdentifier(),
                            timestamp = event.timestamp.toEpochMilliseconds(),
                            payload = encoded.text,
                            run_id = runId,
                            sequence = sequence,
                            caused_by = envelope.causedBy,
                            recorded_at = recordedAt.toEpochMilliseconds(),
                            truncated = if (encoded.truncated) 1L else 0L,
                        )
                        StoredEvent(
                            event = event,
                            sequence = sequence,
                            recordedAt = recordedAt,
                            causedBy = envelope.causedBy,
                            runId = runId,
                            truncated = encoded.truncated,
                        )
                    }

                    // Under the same lock as the insert, so two writers cannot sweep the store
                    // concurrently and delete rows out from under each other's accounting.
                    bytesSinceSweep += encoded.storedBytes
                    val pruned = if (bytesSinceSweep >= sweepIntervalBytes) {
                        bytesSinceSweep = 0
                        pruneToBudget(newestSequence = stored.sequence, prunedAt = recordedAt)
                    } else {
                        null
                    }

                    SaveOutcome(
                        stored = stored,
                        signals = buildList {
                            if (encoded.truncated) {
                                add(
                                    EventStoreSignal.PayloadTruncated(
                                        eventId = event.eventId,
                                        eventType = event.eventType,
                                        originalBytes = encoded.originalBytes,
                                        storedBytes = encoded.storedBytes,
                                    ),
                                )
                            }
                            pruned?.let(::add)
                        },
                    )
                }
            }.onSuccess { outcome ->
                outcome.signals.forEach { _signals.tryEmit(it) }
            }.onFailure { throwable ->
                _signals.tryEmit(
                    EventStoreSignal.PersistenceFailed(
                        eventId = event.eventId,
                        eventType = event.eventType,
                        failure = EventStoreFailure.classify(throwable),
                        reason = throwable.message.orEmpty(),
                    ),
                )
            }.map { outcome ->
                outcome.stored
            }
        }

    /**
     * Rotate the oldest rows out until payload bytes are back under [pruneToBytes], and record
     * the pass. Returns null when the store was within budget, so nothing was dropped.
     *
     * Oldest-first rather than the tail-drop `TraceRecorder` uses: a trace is finalized once and
     * can afford to lose its end, but the store is written forever, and a store that dropped the
     * newest events would simply stop recording once it filled. [newestSequence] — the write
     * that triggered this sweep — is held out of it for the same reason.
     *
     * Runs inside [sequenceLock] and in one transaction, so a reader never observes a
     * half-rotated store or a drop that has no marker.
     */
    private fun pruneToBudget(newestSequence: Long, prunedAt: Instant): EventStoreSignal.EventsPruned? =
        database.transactionWithResult {
            val bytesBefore = queries.totalPayloadBytes().executeAsOne()
            if (bytesBefore <= maxStoreBytes) return@transactionWithResult null

            val bytesToFree = bytesBefore - pruneToBytes
            var droppedBytes = 0L
            var droppedCount = 0L
            var firstSequence: Long? = null
            var lastSequence = 0L

            while (droppedBytes < bytesToFree) {
                val batch = queries
                    .oldestPayloadSizes(
                        beforeSequence = newestSequence,
                        limit = EventStoreBudget.PRUNE_BATCH_SIZE,
                    )
                    .executeAsList()
                if (batch.isEmpty()) break

                var cutoff = 0L
                for (row in batch) {
                    droppedBytes += row.payload_bytes
                    droppedCount++
                    if (firstSequence == null) firstSequence = row.sequence
                    lastSequence = row.sequence
                    cutoff = row.sequence
                    if (droppedBytes >= bytesToFree) break
                }
                queries.deleteEventsUpToSequence(cutoff)
            }

            if (droppedCount == 0L) return@transactionWithResult null

            val bytesAfter = bytesBefore - droppedBytes
            pruningQueries.insertPruning(
                pruned_at = prunedAt.toEpochMilliseconds(),
                dropped_count = droppedCount,
                dropped_bytes = droppedBytes,
                first_sequence = firstSequence ?: 0L,
                last_sequence = lastSequence,
                store_bytes_before = bytesBefore,
                store_bytes_after = bytesAfter,
            )
            pruningQueries.trimPruningRecords(EventStoreBudget.MAX_PRUNING_RECORDS)

            EventStoreSignal.EventsPruned(
                prunedAt = prunedAt,
                droppedCount = droppedCount,
                droppedBytes = droppedBytes,
                firstSequence = firstSequence ?: 0L,
                lastSequence = lastSequence,
                storeBytesBefore = bytesBefore,
                storeBytesAfter = bytesAfter,
            )
        }

    /**
     * Every event the rotation policy has dropped from this database, and when (AMPR-301).
     *
     * Newest pass first. A reader whose fold starts above sequence 1 reads this to tell a store
     * that was rotated from one that simply never held those events.
     */
    suspend fun getPruningRecords(): Result<List<EventStorePruning>> =
        withContext(ioDispatcher) {
            runCatching {
                pruningQueries.getPruningRecords().executeAsList()
            }
        }

    /**
     * Retrieve every event whose `sequence` is at or after [sequence], in fold order.
     *
     * Rows this build cannot decode are skipped and counted; see [DecodedRows].
     */
    suspend fun getEventsSinceSequence(sequence: Long): Result<DecodedRows<StoredEvent>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsSinceSequence(sequence)
                    .executeAsList()
            }.map { rows ->
                rows.decodeSkippingUndecodable { row -> row.toStoredEvent() }
            }
        }

    /**
     * Retrieve every event whose envelope names [eventId] as its cause, in fold order.
     *
     * Rows this build cannot decode are skipped and counted; see [DecodedRows].
     */
    suspend fun getEventsCausedBy(eventId: EventId): Result<DecodedRows<StoredEvent>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsCausedBy(eventId)
                    .executeAsList()
            }.map { rows ->
                rows.decodeSkippingUndecodable { row -> row.toStoredEvent() }
            }
        }

    /**
     * Retrieve all events newest first, by `sequence`.
     *
     * Rows this build cannot decode are skipped and counted; see [DecodedRows].
     */
    suspend fun getAllEvents(): Result<DecodedRows<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getAllEvents()
                    .executeAsList()
            }.map { rows ->
                rows.decodeSkippingUndecodable()
            }
        }

    /**
     * Retrieve all events whose timestamp is at or after [timestamp], in fold (`sequence`) order.
     *
     * Rows this build cannot decode are skipped and counted; see [DecodedRows].
     */
    suspend fun getEventsSince(timestamp: Instant): Result<DecodedRows<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsSince(timestamp.toEpochMilliseconds())
                    .executeAsList()
            }.map { rows ->
                rows.decodeSkippingUndecodable()
            }
        }

    /**
     * Retrieve all events filtered by [eventType] (e.g., "TaskCreatedEvent"), newest first.
     *
     * Rows this build cannot decode are skipped and counted; see [DecodedRows].
     */
    suspend fun getEventsByType(eventType: EventType): Result<DecodedRows<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsByType(
                        event_type = eventType,
                    )
                    .executeAsList()
            }.map { rows ->
                rows.decodeSkippingUndecodable()
            }
        }

    /**
     * Retrieve an event by its [eventId], or null if not present.
     *
     * A row that will not decode is a [Result.failure] carrying [EventSerializationException],
     * which [EventStoreFailure.classify] reads as [EventStoreFailure.SERIALIZATION] (AMPR-364).
     * The decode is inside the `runCatching` on purpose: it used to sit in a `Result.map` block,
     * which does not catch, so the exception escaped the `Result` boundary and reached the
     * caller as a throw from a function whose whole contract is that it does not throw.
     */
    suspend fun getEventById(eventId: EventId): Result<Event?> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventById(eventId)
                    .executeAsOneOrNull()
                    ?.let { row -> decode(row.payload, row.event_id) }
            }
        }

    /**
     * Retrieve events between [fromTime] and [toTime] (inclusive), in fold (`sequence`) order.
     *
     * Rows this build cannot decode are skipped and counted; see [DecodedRows].
     */
    suspend fun getEventsBetween(fromTime: Instant, toTime: Instant): Result<DecodedRows<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsBetween(
                        fromTime.toEpochMilliseconds(),
                        toTime.toEpochMilliseconds(),
                    )
                    .executeAsList()
            }.map { rows ->
                rows.decodeSkippingUndecodable()
            }
        }

    /**
     * Retrieve events between [fromTime] and [toTime] with optional filtering by event types and source IDs.
     *
     * This method applies filters at the database level for better performance compared to
     * filtering in memory after retrieving all events.
     *
     * @param fromTime Start of time range (inclusive)
     * @param toTime End of time range (inclusive)
     * @param eventTypes Optional set of event type strings to filter by (e.g., "TaskCreated", "QuestionRaised")
     * @param sourceIds Optional set of source IDs to filter by (agent IDs or "human")
     * @return Result containing the events matching the criteria, in fold (`sequence`) order,
     * plus a count of the rows this build could not decode; see [DecodedRows]
     */
    suspend fun getEventsWithFilters(
        fromTime: Instant,
        toTime: Instant,
        eventTypes: Set<String>? = null,
        sourceIds: Set<String>? = null,
    ): Result<DecodedRows<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                val fromMillis = fromTime.toEpochMilliseconds()
                val toMillis = toTime.toEpochMilliseconds()

                // Choose the appropriate query based on which filters are provided
                val rows = when {
                    // Both filters provided
                    eventTypes != null && sourceIds != null -> {
                        queries.getEventsBetweenWithBothFilters(
                            fromTime = fromMillis,
                            toTime = toMillis,
                            eventTypes = eventTypes,
                            sourceIds = sourceIds,
                        ).executeAsList()
                    }
                    // Only event types filter
                    eventTypes != null -> {
                        queries.getEventsBetweenWithEventTypes(
                            fromTime = fromMillis,
                            toTime = toMillis,
                            eventTypes = eventTypes,
                        ).executeAsList()
                    }
                    // Only source IDs filter
                    sourceIds != null -> {
                        queries.getEventsBetweenWithSourceIds(
                            fromTime = fromMillis,
                            toTime = toMillis,
                            sourceIds = sourceIds,
                        ).executeAsList()
                    }
                    // No filters, use the simple query
                    else -> {
                        queries.getEventsBetween(
                            fromMillis,
                            toMillis,
                        ).executeAsList()
                    }
                }

                rows
            }.map { rows ->
                rows.decodeSkippingUndecodable()
            }
        }

    private companion object {
        /**
         * Serializes sequence assignment across every [EventRepository] in the process. The
         * SQLite JDBC drivers do not isolate concurrent transactions from each other (the
         * in-memory driver shares one connection and one transaction slot across threads; the
         * file driver's deferred `BEGIN` lets two readers both see the same `MAX(sequence)`),
         * so the read-then-insert in [saveEvent] has to be serialized here. Process-wide rather
         * than per instance because the UI's `RepositoryFactory` and the orchestrator can each
         * hold a repository over the same database. The unique index on `sequence` is the
         * backstop.
         */
        val sequenceLock = Mutex()

        /**
         * Slots [signals] holds for a consumer that is not keeping up. Emissions are
         * non-suspending and drop the oldest past this, so a stalled collector never blocks a
         * write and never grows a queue without limit.
         */
        const val SIGNAL_BUFFER_CAPACITY = 64
    }

    /** What [saveEvent] produced: the row it wrote, and what the budget had to do to write it. */
    private class SaveOutcome(
        val stored: StoredEvent,
        val signals: List<EventStoreSignal>,
    )

    /**
     * A serialized event as the row will hold it.
     *
     * @property storedBytes what [text] occupies; equal to [originalBytes] unless [truncated].
     */
    private class EncodedPayload(
        val text: String,
        val truncated: Boolean,
        val originalBytes: Int,
        val storedBytes: Int,
    )

    /**
     * Serialize [event], cutting its oversized string leaves if the result exceeds
     * [maxEventBytes] (AMPR-301).
     *
     * Only *string leaves* longer than [maxStringFieldChars] are cut, and never the class
     * discriminator, so the payload's JSON shape survives and [decode] still reconstructs the
     * event — a shortened record, never an unreadable one.
     *
     * An event that exceeds the budget through field count rather than one long free-text field
     * has nothing to cut and is stored whole. The per-event bound is best-effort by design;
     * [maxStoreBytes] is the hard backstop underneath it.
     */
    private fun encodeWithinBudget(event: Event): EncodedPayload {
        val encoded = encode(event)
        val originalBytes = encoded.encodeToByteArray().size
        if (originalBytes <= maxEventBytes) {
            return EncodedPayload(encoded, truncated = false, originalBytes, originalBytes)
        }

        val (element, wasTruncated) = json
            .parseToJsonElement(encoded)
            .truncateStringLeaves(maxStringFieldChars)
        if (!wasTruncated) {
            return EncodedPayload(encoded, truncated = false, originalBytes, originalBytes)
        }

        val cut = json.encodeToString(JsonElement.serializer(), element)
        return EncodedPayload(cut, truncated = true, originalBytes, cut.encodeToByteArray().size)
    }

    private fun encode(event: Event): String = try {
        json.encodeToString(
            serializer = Event.serializer(),
            value = event,
        )
    } catch (throwable: SerializationException) {
        throw EventSerializationException(
            message = "Failed to serialize event ${event.eventId}",
            cause = throwable,
        )
    } catch (throwable: Throwable) {
        throw EventSerializationException(
            message = "Failed to serialize event ${event.eventId}",
            cause = throwable,
        )
    }

    /**
     * Decode every row that decodes, skip the ones that do not, and say which (AMPR-364).
     *
     * One undecodable row used to fail the whole query: the decode sat in a `Result.map` block,
     * and `Result.map` does not catch, so an `EventSerializationException` escaped the `Result`
     * boundary and took every good row with it. A payload naming an [Event] subtype this build
     * does not have is reachable by version skew alone, with no third-party extension involved.
     *
     * Each skip is announced on [signals] as [EventStoreSignal.RowUndecodable] and named on the
     * returned [DecodedRows], so a dropped row is never silent: this store cannot publish an
     * `Event` about its own reads — the door above it does that, from the count it reads here.
     */
    private fun List<EventStore>.decodeSkippingUndecodable(): DecodedRows<Event> =
        decodeSkippingUndecodable { row -> decode(row.payload, row.event_id) }

    private fun <T : Any> List<EventStore>.decodeSkippingUndecodable(
        transform: (EventStore) -> T,
    ): DecodedRows<T> {
        val skipped = mutableListOf<UndecodableRow>()
        val decoded = mapNotNull { row ->
            try {
                transform(row)
            } catch (throwable: Throwable) {
                val reason = throwable.decodeReason()
                skipped += UndecodableRow(rowId = row.event_id, reason = reason)
                _signals.tryEmit(
                    EventStoreSignal.RowUndecodable(
                        eventId = row.event_id,
                        eventType = row.event_type,
                        reason = reason,
                    ),
                )
                null
            }
        }
        return DecodedRows(decoded, skipped)
    }

    /**
     * The decoder's own complaint, preferred over the wrapper's. [decode] wraps everything in
     * [EventSerializationException], whose message names the row; the cause is the one that says
     * *why* — an unknown class discriminator reads very differently from malformed JSON.
     */
    private fun Throwable.decodeReason(): String =
        (cause ?: this).message ?: this::class.simpleName.orEmpty()

    private fun EventStore.toStoredEvent(): StoredEvent =
        StoredEvent(
            event = decode(payload, event_id),
            sequence = sequence,
            recordedAt = Instant.fromEpochMilliseconds(recorded_at),
            causedBy = caused_by,
            runId = run_id,
            truncated = truncated != 0L,
        )

    /**
     * Decode one payload, or throw [EventSerializationException] naming the row it came from.
     *
     * Every caller either catches this — see [decodeSkippingUndecodable] — or lets `runCatching`
     * turn it into the `Result.failure` a single-row read returns. Nothing lets it escape a
     * `Result`.
     */
    private fun decode(payload: String, rowId: EventId): Event = try {
        json.decodeFromString(
            deserializer = Event.serializer(),
            string = payload,
        )
    } catch (throwable: SerializationException) {
        throw EventSerializationException(
            message = "Failed to deserialize event payload for row $rowId",
            cause = throwable,
        )
    } catch (throwable: Throwable) {
        throw EventSerializationException(
            message = "Failed to deserialize event payload for row $rowId",
            cause = throwable,
        )
    }
}
