package link.socket.ampere.agents.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.events.utils.EventSerializationException
import link.socket.ampere.data.Repository
import link.socket.ampere.db.Database
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.db.events.EventStoreQueries
import link.socket.ampere.util.ioDispatcher

/**
 * Repository responsible for persisting and querying Events using SQLDelight.
 *
 * This lives in common code and works across KMP targets. Callers are responsible for
 * providing a platform-specific SQLDelight `SqlDriver` to construct the generated [Database]
 * instance and then pass it into this repository.
 */
// TODO: Remove duplication
class EventRepository(
    override val json: Json,
    override val scope: CoroutineScope,
    private val database: Database,
) : Repository<EventId, Event>(json, scope) {

    override val tag: String = "Event${super.tag}"

    private val queries: EventStoreQueries
        get() = database.eventStoreQueries

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
     * The envelope's `runId` wins; when it is null the deprecated per-kind [runIdOrNull]
     * fallback fills `run_id` until every publisher passes it explicitly.
     */
    @Suppress("DEPRECATION")
    suspend fun saveEvent(
        event: Event,
        envelope: EventEnvelope = EventEnvelope(),
        recordedAt: Instant = event.timestamp,
    ): Result<StoredEvent> =
        withContext(ioDispatcher) {
            runCatching {
                val eventPayload: String = encode(event)
                val runId = envelope.runId ?: event.runIdOrNull()

                sequenceLock.withLock {
                    database.transactionWithResult {
                        val sequence = queries.nextSequence().executeAsOne()
                        queries.insertEvent(
                            event_id = event.eventId,
                            event_type = event.eventType,
                            source_id = event.eventSource.getIdentifier(),
                            timestamp = event.timestamp.toEpochMilliseconds(),
                            payload = eventPayload,
                            run_id = runId,
                            sequence = sequence,
                            caused_by = envelope.causedBy,
                            recorded_at = recordedAt.toEpochMilliseconds(),
                        )
                        StoredEvent(
                            event = event,
                            sequence = sequence,
                            recordedAt = recordedAt,
                            causedBy = envelope.causedBy,
                            runId = runId,
                        )
                    }
                }
            }
        }

    /**
     * Retrieve every event whose `sequence` is at or after [sequence], in fold order.
     */
    suspend fun getEventsSinceSequence(sequence: Long): Result<List<StoredEvent>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsSinceSequence(sequence)
                    .executeAsList()
            }.map { rows ->
                rows.map { row -> row.toStoredEvent() }
            }
        }

    /**
     * Retrieve every event whose envelope names [eventId] as its cause, in fold order.
     */
    suspend fun getEventsCausedBy(eventId: EventId): Result<List<StoredEvent>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsCausedBy(eventId)
                    .executeAsList()
            }.map { rows ->
                rows.map { row -> row.toStoredEvent() }
            }
        }

    /**
     * Retrieve all events newest first, by `sequence`.
     */
    suspend fun getAllEvents(): Result<List<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getAllEvents()
                    .executeAsList()
            }.map { rows ->
                rows.map { row ->
                    decode(row.payload)
                }
            }
        }

    /**
     * Retrieve all events whose timestamp is at or after [timestamp], in fold (`sequence`) order.
     */
    suspend fun getEventsSince(timestamp: Instant): Result<List<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsSince(timestamp.toEpochMilliseconds())
                    .executeAsList()
            }.map { rows ->
                rows.map { row ->
                    decode(row.payload)
                }
            }
        }

    /**
     * Retrieve all events filtered by [eventType] (e.g., "TaskCreatedEvent"), newest first.
     */
    suspend fun getEventsByType(eventType: EventType): Result<List<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsByType(
                        event_type = eventType,
                    )
                    .executeAsList()
            }.map { rows ->
                rows.map { row ->
                    decode(row.payload)
                }
            }
        }

    /**
     * Retrieve an event by its [eventId], or null if not present.
     */
    suspend fun getEventById(eventId: EventId): Result<Event?> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventById(eventId)
                    .executeAsOneOrNull()
            }.map { row ->
                if (row == null) {
                    null
                } else {
                    decode(row.payload)
                }
            }
        }

    /**
     * Retrieve events between [fromTime] and [toTime] (inclusive), in fold (`sequence`) order.
     */
    suspend fun getEventsBetween(fromTime: Instant, toTime: Instant): Result<List<Event>> =
        withContext(ioDispatcher) {
            runCatching {
                queries
                    .getEventsBetween(
                        fromTime.toEpochMilliseconds(),
                        toTime.toEpochMilliseconds(),
                    )
                    .executeAsList()
            }.map { rows ->
                rows.map { row ->
                    decode(row.payload)
                }
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
     * @return Result containing list of events matching the criteria, in fold (`sequence`) order
     */
    suspend fun getEventsWithFilters(
        fromTime: Instant,
        toTime: Instant,
        eventTypes: Set<String>? = null,
        sourceIds: Set<String>? = null,
    ): Result<List<Event>> =
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
                rows.map { row ->
                    decode(row.payload)
                }
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

    private fun EventStore.toStoredEvent(): StoredEvent =
        StoredEvent(
            event = decode(payload),
            sequence = sequence,
            recordedAt = Instant.fromEpochMilliseconds(recorded_at),
            causedBy = caused_by,
            runId = run_id,
        )

    private fun decode(payload: String): Event = try {
        json.decodeFromString(
            deserializer = Event.serializer(),
            string = payload,
        )
    } catch (throwable: SerializationException) {
        throw EventSerializationException(
            message = "Failed to deserialize event payload",
            cause = throwable,
        )
    } catch (throwable: Throwable) {
        throw EventSerializationException(
            message = "Failed to deserialize event payload",
            cause = throwable,
        )
    }
}

/**
 * The per-kind `run_id` lookup that predates the envelope (recon C9). Only the ten kinds below
 * ever stored a run id; every other kind stored NULL.
 */
@Deprecated("F4: fallback until every publisher passes runId; removed by the W1 lock ticket")
private fun Event.runIdOrNull(): RunId? = when (this) {
    is ProviderCallStartedEvent -> workflowId
    is ProviderCallCompletedEvent -> workflowId
    is ToolEvent.ToolExecutionStarted -> runId
    is ToolEvent.ToolExecutionCompleted -> runId
    is MemoryEvent.KnowledgeStored -> runId
    is MemoryEvent.KnowledgeRecalled -> runId
    is MemoryEvent.MilestoneReached -> runId
    is link.socket.ampere.agents.domain.event.TaskEvent.TaskCompleted -> runId
    is link.socket.ampere.agents.domain.event.TaskEvent.TaskFailed -> runId
    is EmissionEvent.Produced -> emission.provenance.runId
    else -> null
}
