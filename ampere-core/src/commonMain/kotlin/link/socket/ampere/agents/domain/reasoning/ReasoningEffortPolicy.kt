package link.socket.ampere.agents.domain.reasoning

import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.ThinkingLevel

/**
 * The `reasoning_effort` one call sends, for a model that takes one from Ampere
 * (AMPR-488): Gemini 3 and later, and OpenAI's GPT-5.5, GPT-5.6 and GPT-6
 * (AMPR-501), which all default to `medium`.
 *
 * A thinking model's output cap includes its thought tokens. Gemini 3 and later
 * think by default (at `medium` on 3.8 Flash and Gemini 4 Argon, at `high` on 3.1
 * Pro), and a call whose cap the thinking spends comes back truncated or empty.
 * Ampere's PROPEL calls carry small caps (500 for PERCEIVE, 1000 for PLAN and the
 * outcome evaluation), so the effort is chosen per call rather than left to the
 * model.
 *
 * Lowering the effort was chosen over raising the caps: a raised cap pays for
 * thinking a short structured answer does not need, and moves every call's cost
 * and latency rather than only the calls that were truncated. Google's
 * OpenAI-compatible endpoint maps `reasoning_effort` onto `thinking_level`;
 * OpenAI's Chat Completions takes it as is.
 *
 * `none` is never sent. OpenAI documents Chat Completions function calling on
 * GPT-5.5 onward only at effort `none`, so a `tools` request to those models
 * would need it. Ampere's own calls carry no `tools`, so nothing here depends on
 * that today.
 *
 * The effort is the lower of two levels:
 *
 * - **what the call asks for**: the higher of [RoutingContext.preferredReasoning]
 *   and the requirement's `minReasoning`, read LOW → `low`, NORMAL → `medium`,
 *   HIGH → `high`. A call that asks for nothing asks for the model's default.
 * - **what its output budget affords**: `low` up to [LOW_EFFORT_MAX_TOKENS], so
 *   every QUICK-class call (a short answer under a small cap) thinks at `low`
 *   or less;
 *   `medium` up to [MEDIUM_EFFORT_MAX_TOKENS]; `high` above.
 *
 * When that is [ThinkingLevel.MINIMAL], which only happens for a model that
 * already defaults to it, no effort is sent: some models reject the name.
 *
 * The capability rung is not read. A rung on a call is a floor on which model
 * may serve it, composed upward from the agent's own declaration, and every PROPEL
 * agent declares one — it says nothing about how hard this particular call is.
 */
internal object ReasoningEffortPolicy {

    /** The largest output cap that sends `low`. */
    const val LOW_EFFORT_MAX_TOKENS: Int = 2_000

    /** The largest output cap that sends at most `medium`. */
    const val MEDIUM_EFFORT_MAX_TOKENS: Int = 8_000

    /**
     * The effort for a call to [model] with [maxTokens] of output under
     * [routingContext], or null when the request should carry none: the model
     * takes no effort from Ampere, or its own default is already below `low`.
     * Never [ThinkingLevel.MINIMAL].
     */
    fun effortFor(
        model: AIModel,
        maxTokens: Int,
        routingContext: RoutingContext?,
    ): ThinkingLevel? {
        val modelDefault = model.features.defaultThinking ?: return null
        val asked = askedLevel(routingContext) ?: modelDefault
        return minOf(asked, affordableLevel(maxTokens)).takeIf { it != ThinkingLevel.MINIMAL }
    }

    /** The wire value of [level]: `reasoning_effort` spells the levels in lower case. */
    fun wireValue(level: ThinkingLevel): String = level.name.lowercase()

    private fun askedLevel(routingContext: RoutingContext?): ThinkingLevel? =
        listOfNotNull(
            routingContext?.preferredReasoning,
            routingContext?.requirements?.minReasoning,
        ).maxOrNull()?.toThinkingLevel()

    private fun affordableLevel(maxTokens: Int): ThinkingLevel = when {
        maxTokens <= LOW_EFFORT_MAX_TOKENS -> ThinkingLevel.LOW
        maxTokens <= MEDIUM_EFFORT_MAX_TOKENS -> ThinkingLevel.MEDIUM
        else -> ThinkingLevel.HIGH
    }

    private fun RelativeReasoning.toThinkingLevel(): ThinkingLevel = when (this) {
        RelativeReasoning.LOW -> ThinkingLevel.LOW
        RelativeReasoning.NORMAL -> ThinkingLevel.MEDIUM
        RelativeReasoning.HIGH -> ThinkingLevel.HIGH
    }
}
