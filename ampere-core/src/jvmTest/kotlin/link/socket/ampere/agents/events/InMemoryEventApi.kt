package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database

/**
 * An in-memory door for jvmTest: a fresh SQLite database brought to the current schema by
 * [DatabaseSchemaManager], an [EventSerialBus] on [CoroutineScope], an [EventRepository], and an
 * [AgentEventApi] reading time from the given [Clock].
 *
 * Use [create] when a test only needs the api and repository; use [open] when it also needs the
 * driver or database (to drop a table, read raw rows, or close the connection).
 */
object InMemoryEventApi {

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

    fun open(
        agentId: AgentId,
        clock: Clock = Clock.System,
        scope: CoroutineScope,
    ): Handle {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseSchemaManager.ensure(driver).getOrThrow()
        val database = Database(driver)
        val bus = EventSerialBus(scope)
        val repository = EventRepository(DEFAULT_JSON, scope, database)
        val api = AgentEventApi(
            agentId = agentId,
            eventRepository = repository,
            eventSerialBus = bus,
            clock = clock,
        )
        return Handle(api, repository, database, driver, bus)
    }

    fun create(
        agentId: AgentId,
        clock: Clock = Clock.System,
        scope: CoroutineScope,
    ): Pair<AgentEventApi, EventRepository> =
        open(agentId = agentId, clock = clock, scope = scope).let { it.api to it.repository }
}
