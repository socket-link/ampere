package link.socket.ampere.api

import link.socket.ampere.agents.definition.AgentFactory
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
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.propel.RunHost

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
 * ampere.events.observe().collect { event -> ... }
 * ampere.status.snapshot()
 *
 * ampere.close()
 * ```
 *
 * [runs] is the hosted run: one run per goal over a roster of seats, every phase on the
 * record (AMPR-393). `agents.pursue` opens one over the roster `agents.team {}` declared.
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
     * Opens hosted runs: one run per goal, over a roster of seats (AMPR-393).
     *
     * The entry into the PROPEL loop. The host seat perceives, recalls, observes and
     * plans; the consumer may hold the plan at a
     * [PlanGate][link.socket.ampere.propel.PlanGate]; each step runs on the seat the
     * plan assigned it to; closing the run learns. Every event carries the run id, so
     * `ArcTraceProjection.project(runId)` is the whole run.
     *
     * ```
     * val run = ampere.runs!!.open(roster, seats, goal, tools)
     * try { run.execute() } finally { run.close() }
     * ```
     *
     * `null` on instances that carry no agent-capable environment (the stub); the
     * [Ampere.fromEnvironment] path always supplies one. The seats it builds dispatch
     * through that instance's executor, the same way [agentFactory]'s agents do; the
     * tools are each run's own, declared when it is opened.
     */
    val runs: RunHost?
        get() = null

    /**
     * Runtime transport for outbound LLM calls.
     *
     * Set at construction time via [Ampere.fromEnvironment] (or its
     * platform-specific siblings) and already wired into [agentFactory], so
     * every agent built off this instance shares it — no per-construction-site
     * re-plumbing required.
     *
     * `null` means no transport was supplied. Agents built in that state throw
     * [MissingUpstreamLlmClientException][link.socket.ampere.llm.MissingUpstreamLlmClientException]
     * on their first LLM call rather than silently egressing to a provider
     * (AMPR-236). Pass [BundledUpstreamLlmClient] to opt into the direct
     * per-provider call.
     */
    val upstreamLlmClient: UpstreamLlmClient?
        get() = null

    /**
     * Runtime transport for outbound decision calls (AMPR-384), the sibling of
     * [upstreamLlmClient] for the Decide call kind. Set via
     * [Ampere.fromEnvironment] and already wired into [agentFactory].
     *
     * `null` means none was supplied; an agent's first `decide` then throws
     * [MissingUpstreamDecisionClientException][link.socket.ampere.llm.decide.MissingUpstreamDecisionClientException].
     * A transport is opted into, never inherited.
     */
    val upstreamDecisionClient: UpstreamDecisionClient?
        get() = null

    /**
     * Agent factory bound to this instance's environment and
     * [upstreamLlmClient].
     *
     * Constructing agents through this factory is what makes the injected
     * transport govern their LLM calls. `null` on instances that carry no
     * agent-capable environment (e.g. the stub instance); the
     * [Ampere.fromEnvironment] path always supplies one.
     */
    val agentFactory: AgentFactory?
        get() = null
}
