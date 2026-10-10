package link.socket.ampere.propel

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.probe.Probe
import link.socket.ampere.roster.RoleId

/**
 * One agent, filling one seat, for one run (AMPR-385 row H1).
 *
 * A [link.socket.ampere.roster.Roster] says what the seats are; a `HostedAgent` says who
 * fills one of them this run. Everything an agent needs that a roster cannot know —
 * its sparks, its charter, which model it talks to, which transport that call goes
 * out on, whose memory it reads and writes — is declared here, so the roster stays a
 * value a consumer can serialise and this stays the thing it binds to at run time.
 *
 * The run builds the agent itself from this: a
 * [SparkBasedAgent][link.socket.ampere.agents.definition.SparkBasedAgent] with
 * [sparks] stacked, its own event door, its own reasoning unit, and the run id on
 * every event it publishes. A consumer never constructs the agent, which is what
 * keeps a hosted run's phases the same phases `FlowPhase` runs.
 *
 * @property id The agent's identity. Also its event door's identity
 *   (`EnvironmentService.createEventApi(id)`), so every event this seat publishes is
 *   attributed to it; two seats of one run must therefore have different ids.
 * @property role The seat this agent fills. Must name a role of the roster the run
 *   is opened over.
 * @property sparks The stack this agent is differentiated by, applied in order, each
 *   publishing a `SparkAppliedEvent` as the run charges. Sparks narrow and never
 *   widen: a tool no spark's `allowedTools` admits is unreachable for this seat even
 *   when its role declares it (AMPR-400). `Spark.fromMarkdown` (AMPR-392) is how a
 *   consumer turns its own `.spark.md` catalog into these.
 * @property charter What this seat is for, in words, prepended to its system prompt
 *   ahead of the cognitive-context header. Null declares none and the prompt is
 *   exactly what the spark stack builds.
 * @property aiConfiguration The provider and model this seat's calls default to. A
 *   [cognitiveRelay] overrides it per call; the seat's
 *   [RoleConfig.execution][link.socket.ampere.roster.RoleConfig.execution] reaches
 *   the relay as routing tags (`withSeat`), not as a rule of its own.
 * @property cognitiveRelay Capability- and cost-aware routing for this seat's calls
 *   (AMPR-219). Null keeps [aiConfiguration] as the static answer.
 * @property upstreamLlmClient Where this seat's model calls go. **Required, never
 *   defaulted** (AMPR-236): a transport is opted into, so a seat with nowhere to send
 *   a call says so at construction rather than egressing to a provider by accident.
 *   Pass [BundledUpstreamLlmClient][link.socket.ampere.llm.BundledUpstreamLlmClient]
 *   to opt into the direct per-provider call.
 * @property memory This seat's durable memory: `knowledge` is what RECALL reads and
 *   what LEARN writes, `outcomes` is where the run's own outcome is recorded. Null
 *   gives a seat with no memory — RECALL finds nothing and LEARN stores nothing, and
 *   the run still closes. Ampere's own `KnowledgeStore` has no holder column, so two
 *   seats sharing one store recall each other's knowledge; per-seat separation is
 *   whatever the consumer's store scopes (AMPR-385 row H22, no verdict).
 * @property affinity How this seat thinks, the base of its prompt beneath the sparks.
 * @property probes Predicates this seat evaluates in OBSERVE, as the roster's
 *   verifier. Only the verifier seat's probes run, and only when the roster names one
 *   (AMPR-409); every other seat's are ignored, because OBSERVE is one phase of the
 *   run rather than one per seat. The run wraps them in a
 *   [ProbeSuite][link.socket.ampere.probe.ProbeSuite] built around this seat's own
 *   door, so each `VerdictReached` is attributed to the verifier and carries the run.
 */
@AmpereStableApi
data class HostedAgent(
    val id: AgentId,
    val role: RoleId,
    val sparks: List<Spark> = emptyList(),
    val charter: String? = null,
    val aiConfiguration: AIConfiguration,
    val cognitiveRelay: CognitiveRelay? = null,
    val upstreamLlmClient: UpstreamLlmClient,
    val memory: MemoryStore? = null,
    val affinity: CognitiveAffinity = CognitiveAffinity.INTEGRATIVE,
    val probes: List<Probe<RunObservation>> = emptyList(),
) {

    companion object {

        /**
         * A [HostedAgent] built from plain ids, for a caller that cannot write a [RoleId].
         *
         * [RoleId] is an inline value class, which the Objective-C export erases to
         * `Any?` and whose companion it does not export, so Swift can neither construct
         * one nor name the type — the same reason
         * [RoleConfig.of][link.socket.ampere.roster.RoleConfig.of] exists. A seat the
         * consumer cannot author on the platform it renders on is not authorable.
         * Kotlin callers should use the constructor.
         */
        fun of(
            id: AgentId,
            role: String,
            aiConfiguration: AIConfiguration,
            upstreamLlmClient: UpstreamLlmClient,
            sparks: List<Spark> = emptyList(),
            charter: String? = null,
            cognitiveRelay: CognitiveRelay? = null,
            memory: MemoryStore? = null,
            affinity: CognitiveAffinity = CognitiveAffinity.INTEGRATIVE,
            probes: List<Probe<RunObservation>> = emptyList(),
        ): HostedAgent = HostedAgent(
            id = id,
            role = RoleId(role),
            sparks = sparks,
            charter = charter,
            aiConfiguration = aiConfiguration,
            cognitiveRelay = cognitiveRelay,
            upstreamLlmClient = upstreamLlmClient,
            memory = memory,
            affinity = affinity,
            probes = probes,
        )
    }
}
