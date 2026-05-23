package link.socket.ampere.api

import link.socket.ampere.api.service.AgentService
import link.socket.ampere.api.service.EventService
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.api.service.OutcomeService
import link.socket.ampere.api.service.PricingService
import link.socket.ampere.api.service.StatusService
import link.socket.ampere.api.service.ThreadService
import link.socket.ampere.api.service.TicketService
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * A running AMPERE instance. Provides access to all SDK subsystems.
 *
 * Obtain an instance via [Ampere.create]:
 * ```
 * val ampere = Ampere.create {
 *     provider("anthropic", "sonnet-4")
 *     workspace("/path/to/project")
 * }
 *
 * ampere.agents.pursue("Build authentication system")
 * ampere.events.observe().collect { event -> ... }
 *
 * ampere.close()
 * ```
 */
@AmpereStableApi
interface AmpereInstance : AutoCloseable {

    /** Agent lifecycle and team management */
    val agents: AgentService

    /** Ticket creation, assignment, and status management */
    val tickets: TicketService

    /** Message threads and inter-agent communication */
    val threads: ThreadService

    /** Event stream observation and querying */
    val events: EventService

    /** Execution history and outcome tracking */
    val outcomes: OutcomeService

    /** Bundled model pricing, overrides, and cost estimation */
    val pricing: PricingService

    /** Persistent knowledge and memory */
    val knowledge: KnowledgeService

    /** System-wide status and health */
    val status: StatusService

    /**
     * Runtime default for outbound LLM calls.
     *
     * Set at construction time via [Ampere.fromEnvironment] (or its
     * platform-specific siblings). Code that constructs agents off this
     * instance should pass this client into agent factories so every
     * agent inherits the same upstream routing — Socket's typical pattern.
     *
     * Defaults to [BundledUpstreamLlmClient] (direct per-provider OpenAI
     * call) when not overridden.
     */
    val upstreamLlmClient: UpstreamLlmClient
        get() = BundledUpstreamLlmClient
}
