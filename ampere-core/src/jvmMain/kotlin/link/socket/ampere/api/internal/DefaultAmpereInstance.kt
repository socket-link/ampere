package link.socket.ampere.api.internal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.agents.service.TicketActionService
import link.socket.ampere.api.AmpereConfig
import link.socket.ampere.api.AmpereInstance
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.api.service.EventService
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.api.service.OutcomeService
import link.socket.ampere.api.service.StatusService
import link.socket.ampere.api.service.ThreadService
import link.socket.ampere.api.service.TicketService
import link.socket.ampere.db.Database

/**
 * JVM implementation of [AmpereInstance].
 *
 * Wires up all SDK services by delegating to existing infrastructure
 * via [EnvironmentService].
 */
internal class DefaultAmpereInstance(
    config: AmpereConfig,
) : AmpereInstance {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val databasePath: String = config.databasePath ?: defaultDatabasePath()

    private val driver: JdbcSqliteDriver = run {
        val dbFile = File(databasePath)
        dbFile.parentFile?.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:$databasePath")
    }

    private val database: Database = run {
        try {
            Database.Schema.create(driver)
        } catch (_: Exception) {
            // Schema may already exist
        }
        Database(driver)
    }

    private val environmentService: EnvironmentService = EnvironmentService.create(
        database = database,
        scope = scope,
    )

    private val sdkEventApi = environmentService.createEventApi("sdk")

    override val agents: AgentService = DefaultAgentService(
        agentActionService = AgentActionService(eventApi = sdkEventApi),
        eventApi = sdkEventApi,
    )

    override val tickets: TicketService = DefaultTicketService(
        actionService = TicketActionService(
            ticketRepository = environmentService.ticketRepository,
            eventApi = sdkEventApi,
        ),
        viewService = DefaultTicketViewService(
            ticketRepository = environmentService.ticketRepository,
        ),
        ticketRepository = environmentService.ticketRepository,
    )

    override val threads: ThreadService = DefaultThreadService(
        actionService = MessageActionService(
            messageRepository = environmentService.messageRepository,
            eventApi = sdkEventApi,
        ),
        viewService = DefaultThreadViewService(
            messageRepository = environmentService.messageRepository,
        ),
    )

    override val events: EventService = DefaultEventService(
        eventRelayService = environmentService.eventRelayService,
        eventRepository = environmentService.eventRepository,
    )

    override val outcomes: OutcomeService = DefaultOutcomeService(
        outcomeRepository = environmentService.outcomeMemoryRepository,
    )

    override val knowledge: KnowledgeService = DefaultKnowledgeService(
        knowledgeRepository = KnowledgeRepositoryImpl(database),
    )

    override val status: StatusService = DefaultStatusService(
        threadViewService = DefaultThreadViewService(
            messageRepository = environmentService.messageRepository,
        ),
        ticketViewService = DefaultTicketViewService(
            ticketRepository = environmentService.ticketRepository,
        ),
        workspace = config.workspace,
    )

    init {
        environmentService.start()
    }

    override fun close() {
        scope.cancel()
        driver.close()
    }

    companion object {
        private fun defaultDatabasePath(): String {
            val homeDir = System.getProperty("user.home")
                ?: System.getProperty("user.dir")
                ?: "."
            return File(homeDir, ".ampere/ampere.db").absolutePath
        }
    }
}
