package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database

/**
 * A JVM test door onto a fresh in-memory store (AMPR-340).
 *
 * `EventSerialBus.publish` is `internal` to `ampere-core`: the only way an event enters the
 * system is [AgentEventApi.publish], which persists it and then dispatches it on the bus. A
 * test outside `ampere-core` that wants to drive a bus subscriber therefore needs a real door,
 * and this is the smallest one — a SQLite database brought to the current schema by
 * [DatabaseSchemaManager], an [EventSerialBus] on [scope], an [EventRepository], and an
 * [AgentEventApi] reading time from [clock].
 *
 * ```kotlin
 * val door = InMemoryEventDoor.open(agentId = "test", scope = scope)
 * bridge = SomeBusConsumer(bus = door.bus)
 * door.api.publish(event).getOrThrow()   // persisted, then delivered to the bridge
 * door.close()
 * ```
 */
object InMemoryEventDoor {

    /** Everything [open] built, so a test can reach past the api when it has to. */
    class Handle(
        val api: AgentEventApi,
        val repository: EventRepository,
        val database: Database,
        val driver: JdbcSqliteDriver,
        val bus: EventSerialBus,
    ) : AutoCloseable {
        override fun close() = driver.close()
    }

    /**
     * Open a door for [agentId]. The caller owns the returned [Handle] and closes it.
     *
     * @param scope the bus dispatches handlers on this scope; the repository uses it too.
     * @param clock what [AgentEventApi] stamps `recorded_at` with.
     * @param logger the bus's logger, for a test that counts subscriptions or errors.
     */
    fun open(
        agentId: AgentId,
        scope: CoroutineScope,
        clock: Clock = Clock.System,
        logger: EventLogger = ConsoleEventLogger(),
    ): Handle {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseSchemaManager.ensure(driver).getOrThrow()
        val database = Database(driver)
        val bus = EventSerialBus(scope, logger)
        val repository = EventRepository(DEFAULT_JSON, scope, database)
        val api = AgentEventApi(
            agentId = agentId,
            eventRepository = repository,
            eventSerialBus = bus,
            clock = clock,
        )
        return Handle(api, repository, database, driver, bus)
    }
}
