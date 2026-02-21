package link.socket.ampere.api.service.stub

import link.socket.ampere.api.AmpereInstance
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.api.service.EventService
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.api.service.OutcomeService
import link.socket.ampere.api.service.StatusService
import link.socket.ampere.api.service.ThreadService
import link.socket.ampere.api.service.TicketService

/**
 * Stub [AmpereInstance] that uses in-memory stub services.
 *
 * No database, no coroutine scope, no real infrastructure — just
 * correct types and sensible defaults for parallel development.
 *
 * ```
 * val ampere = Ampere.createStub()
 * val ticket = ampere.tickets.create("Test ticket").getOrThrow()
 * ampere.close()
 * ```
 */
class StubAmpereInstance : AmpereInstance {

    override val agents: AgentService = StubAgentService()

    override val tickets: TicketService = StubTicketService()

    override val threads: ThreadService = StubThreadService()

    override val events: EventService = StubEventService()

    override val outcomes: OutcomeService = StubOutcomeService()

    override val knowledge: KnowledgeService = StubKnowledgeService()

    override val status: StatusService = StubStatusService()

    override fun close() {
        // No resources to release
    }
}
