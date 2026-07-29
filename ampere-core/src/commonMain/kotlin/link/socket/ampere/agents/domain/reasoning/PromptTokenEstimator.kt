package link.socket.ampere.agents.domain.reasoning

/**
 * Deterministic, provider-agnostic floor estimate of the prompt tokens a request sent.
 *
 * Used only on the cancellation path (AMPR-242). When an in-flight provider call is
 * cancelled, no provider-reported usage ever arrives, but the prompt was demonstrably
 * put on the wire — booking zero would make cancelling free, which is both exploitable
 * and false in the trace. Ampere therefore books the *input* side of the call and zero
 * output: an honest floor computable without a provider round-trip.
 *
 * This is an approximation, not a tokenizer. Ampere has no per-provider BPE vocabulary
 * in common code, so the estimate uses the widely-used ~4-characters-per-token ratio
 * plus a fixed per-message framing overhead. Events settled this way are distinguishable
 * by their `errorType` of [AgentLLMService.CANCELLED_ERROR_TYPE], so consumers that need
 * exactness can exclude them.
 *
 * Replacing this with streamed provider actuals requires an `UpstreamLlmClient`
 * partial-usage surface and is tracked separately as a metering-policy decision.
 */
internal object PromptTokenEstimator {

    /** Average characters per token across common BPE vocabularies. */
    private const val CHARS_PER_TOKEN = 4

    /** Per-message role/delimiter framing the provider adds around each message. */
    private const val PER_MESSAGE_OVERHEAD_TOKENS = 4

    /**
     * Estimates the input tokens for [messageContents], one entry per chat message.
     *
     * Blank and empty entries still cost their framing overhead, matching how providers
     * bill an empty message. Returns `0` only for an empty list.
     */
    fun estimateInputTokens(messageContents: List<String>): Int =
        messageContents.sumOf { content ->
            ceilDiv(content.length, CHARS_PER_TOKEN) + PER_MESSAGE_OVERHEAD_TOKENS
        }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
