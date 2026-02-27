package link.socket.ampere.api

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.api.model.TicketFilter

/**
 * External consumer integration test.
 *
 * Simulates a consumer using the public API exactly as documented:
 * create a stub instance, exercise all 7 services including new methods, close.
 * No internal types or implementation details are referenced.
 */
class ExternalConsumerTest {

    @Test
    fun `full lifecycle through public API only`() = runBlocking {
        val ampere = Ampere.createStub()

        // === Tickets ===
        val ticket = ampere.tickets.create("Implement retry logic") {
            description("Transient auth failures cause immediate failure")
            priority(TicketPriority.HIGH)
            type(TicketType.BUG)
        }.getOrThrow()
        assertTrue(ticket.title == "Implement retry logic")

        ampere.tickets.assign(ticket.id, "engineer-agent").getOrThrow()
        ampere.tickets.transition(ticket.id, TicketStatus.InProgress).getOrThrow()
        // Stub get() returns failure (no persistence) — verify it returns a Result
        assertNotNull(ampere.tickets.get(ticket.id))
        ampere.tickets.list(TicketFilter(priority = TicketPriority.HIGH)).getOrThrow()
        ampere.tickets.list().getOrThrow()

        // === Threads ===
        val thread = ampere.threads.create("Auth design discussion") {
            participants("pm-agent", "engineer-agent")
        }.getOrThrow()
        assertNotNull(thread)

        ampere.threads.post(thread.id, "What about OAuth2 PKCE?").getOrThrow()
        ampere.threads.post(thread.id, "Approved.", senderId = "pm-agent").getOrThrow()
        ampere.threads.get(thread.id).getOrThrow()
        ampere.threads.list().getOrThrow()
        assertNotNull(ampere.threads.observe(thread.id))

        // === Agents ===
        val goalId = ampere.agents.pursue("Build authentication system").getOrThrow()
        assertTrue(goalId.isNotEmpty())
        ampere.agents.wake("reviewer-agent").getOrThrow()
        ampere.agents.listAll()
        ampere.agents.pause("engineer-agent").getOrThrow()

        // === Events (including new get method) ===
        val eventResult = ampere.events.get("nonexistent-event")
        assertTrue(eventResult.isSuccess)
        assertNotNull(ampere.events.observe())
        val from = kotlinx.datetime.Clock.System.now() - kotlin.time.Duration.parse("1h")
        val to = kotlinx.datetime.Clock.System.now()
        ampere.events.query(from, to).getOrThrow()
        ampere.events.query(from, to, sourceIds = setOf("pm-agent")).getOrThrow()
        assertNotNull(ampere.events.replay(from, to))

        // === Outcomes ===
        ampere.outcomes.search("authentication retry", limit = 10).getOrThrow()
        val stats = ampere.outcomes.stats().getOrThrow()
        assertTrue(stats.totalOutcomes == stats.successCount + stats.failureCount)
        ampere.outcomes.forTicket("ticket-123").getOrThrow()
        ampere.outcomes.byExecutor("engineer-agent", limit = 5).getOrThrow()

        // === Knowledge (including new get, search, tags methods) ===
        ampere.knowledge.recall("how did we handle auth?").getOrThrow()
        ampere.knowledge.recall("retry patterns", limit = 10).getOrThrow()

        // New: get by ID
        val knowledgeGetResult = ampere.knowledge.get("nonexistent-id")
        assertTrue(knowledgeGetResult.isSuccess)

        // New: search with various filter combinations
        ampere.knowledge.search(query = "authentication").getOrThrow()
        ampere.knowledge.search(type = KnowledgeType.FROM_OUTCOME).getOrThrow()
        ampere.knowledge.search(
            query = "retry",
            type = KnowledgeType.FROM_OUTCOME,
            tags = listOf("auth"),
            limit = 5,
        ).getOrThrow()

        // New: tags
        ampere.knowledge.tags("knowledge-123").getOrThrow()

        ampere.knowledge.provenance("knowledge-456").getOrThrow()

        // === Status ===
        val snapshot = ampere.status.snapshot().getOrThrow()
        assertNotNull(snapshot)
        assertNotNull(ampere.status.health())

        ampere.close()
    }
}
