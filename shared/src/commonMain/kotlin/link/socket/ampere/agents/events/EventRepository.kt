package link.socket.ampere.agents.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.events.utils.EventSerializationException
import link.socket.ampere.data.Repository
import link.socket.ampere.db.Database
import link.socket.ampere.db.events.EventStoreQueries

/**
 * Repository responsible for persisting and querying Events using SQLDelight.
 *
 * This lives in common code and works across KMP targets. Callers are responsible for
 * providing a platform-specific SQLDelight [SqlDriver] to construct the generated [Database]
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
     * Persist the given [event] by serializing it to JSON and inserting into the event_store table.
     */
    suspend fun saveEvent(event: Event): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val eventPayload: String = encode(event)

                queries.insertEvent(
                    event_id = event.eventId,
                    event_type = event.eventClassType.second,
                    source_id = event.eventSource.getIdentifier(),
                    timestamp = event.timestamp.toEpochMilliseconds(),
                    payload = eventPayload,
                )
            }.map { }
        }

    /**
     * Retrieve all events in reverse chronological order (newest first).
     */
    suspend fun getAllEvents(): Result<List<Event>> =
        withContext(Dispatchers.IO) {
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
     * Retrieve all events since the given epoch millis [timestamp], ascending by time.
     */
    suspend fun getEventsSince(timestamp: Instant): Result<List<Event>> =
        withContext(Dispatchers.IO) {
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
    suspend fun getEventsByType(eventClassType: EventClassType): Result<List<Event>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .getEventsByType(
                        event_type = eventClassType.second,
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
        withContext(Dispatchers.IO) {
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
     * Retrieve events between [fromTime] and [toTime] (inclusive), ascending by time.
     */
    suspend fun getEventsBetween(fromTime: Instant, toTime: Instant): Result<List<Event>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .getEventsBetween(
                        fromTime.toEpochMilliseconds(),
                        toTime.toEpochMilliseconds()
                    )
                    .executeAsList()
            }.map { rows ->
                rows.map { row ->
                    decode(row.payload)
                }
            }
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
