package link.socket.ampere.llm.decide

import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * Public injection seam for outbound decision calls (AMPR-384, J1).
 *
 * The sibling of [link.socket.ampere.llm.UpstreamLlmClient] for the Decide
 * call kind. That seam is typed to chat completions — messages in, text out —
 * and a decision call has no messages and returns no text: it is a state plus
 * typed [Question]s in, [Judgment]s with a measured confidence out. Tunnelling
 * one through the chat seam with structured output would keep a single seam
 * and lose the distribution, which is the whole point of the call.
 *
 * ## No implicit default
 *
 * As with the chat seam (AMPR-236), a transport is opted into, never
 * inherited. There is no default implementation and no silent fallback to the
 * model-backed adapter: an agent that reaches
 * [link.socket.ampere.agents.domain.reasoning.AgentReasoning.decide] without
 * one fails with [MissingUpstreamDecisionClientException]. The three bundled
 * adapters are named explicitly by a caller that wants them:
 *
 * - [HostedSystemOneDecisionClient] — one Ktor POST to a System One endpoint
 *   (Jev through OpenRouter, Clef and Clef-flash on Workers AI). Measured.
 * - [ModelBackedDecisionClient] — the question rendered as a prompt to the
 *   agent's generative model. Self-reported, no distribution.
 * - [DeterministicDecisionClient] — a function. Measured, one-hot.
 *
 * ## Errors
 *
 * Implementations let transport errors propagate. A call that throws leaves no
 * judgment record; a call that returns leaves one per judgment, published by
 * `AgentReasoning.decide` through the agent's event door.
 */
@AmpereStableApi
interface UpstreamDecisionClient {

    /**
     * Ask [request]'s questions about its state.
     *
     * @param configuration The agent's [AIConfiguration]. An adapter that is
     *   itself a model (the model-backed one) may route by it; a hosted
     *   decision endpoint ignores it, as its model is fixed by the endpoint.
     * @return One [Judgment] per question id in [request].
     */
    suspend fun decide(
        request: DecisionRequest,
        configuration: AIConfiguration,
    ): DecisionResponse
}

/**
 * Raised when an agent asks a question with no [UpstreamDecisionClient]
 * configured.
 *
 * Mirrors [link.socket.ampere.llm.MissingUpstreamLlmClientException]: the
 * transport is mandatory, and the fix is to supply one on
 * [link.socket.ampere.agents.config.AgentConfiguration.upstreamDecisionClient]
 * (typically via `Ampere.fromEnvironment(upstreamDecisionClient = ...)` or the
 * agent factory). Nothing falls back to the model-backed adapter: a consumer
 * that wants its generative model to answer names [ModelBackedDecisionClient].
 */
@AmpereStableApi
class MissingUpstreamDecisionClientException(
    agentName: String?,
) : IllegalStateException(
    "No UpstreamDecisionClient configured for " +
        (agentName?.let { "agent '$it'" } ?: "this agent") +
        ". A decision transport is opted into, never inherited: pass an " +
        "UpstreamDecisionClient into the agent factory (or " +
        "Ampere.fromEnvironment(upstreamDecisionClient = ...)), or name " +
        "ModelBackedDecisionClient to have the agent's generative model answer.",
)

/**
 * The transport answered, but not with something a [Judgment] can be built
 * from: an HTTP failure, an answer key the question never declared, a
 * missing probability where the question type requires one.
 */
@AmpereStableApi
class MalformedDecisionResponseException(message: String) : IllegalStateException(message)
