package link.socket.ampere.domain.ai.provider

import link.socket.ampere.domain.ai.model.AIModel

/**
 * The id OpenRouter addresses each bundled model by (AMPR-434).
 *
 * OpenRouter's slug is not the maker's id — `claude-sonnet-4-6` is
 * `anthropic/claude-sonnet-4.6` — and does not follow from it by any rule Ampere
 * could rely on, so every entry is data copied from OpenRouter's model list
 * (`https://openrouter.ai/api/v1/models`, read 2026-10-11, and 2026-10-24 for the
 * October 2026 generation, AMPR-488), never derived.
 * `OpenRouterModelSlugsDriftGuardTest` fails when a bundled model has neither a
 * slug nor a place in [NOT_OFFERED].
 */
object OpenRouterModelSlugs {

    /** Ampere model name to OpenRouter slug. */
    internal val SLUGS: Map<String, String> = mapOf(
        // Anthropic
        "claude-opus-4-5-20251101" to "anthropic/claude-opus-4.5",
        "claude-sonnet-4-5-20250929" to "anthropic/claude-sonnet-4.5",
        "claude-haiku-4-5" to "anthropic/claude-haiku-4.5",
        "claude-opus-4-6" to "anthropic/claude-opus-4.6",
        "claude-opus-4-7" to "anthropic/claude-opus-4.7",
        "claude-opus-4-8" to "anthropic/claude-opus-4.8",
        "claude-opus-5" to "anthropic/claude-opus-5",
        "claude-sonnet-4-6" to "anthropic/claude-sonnet-4.6",
        "claude-sonnet-5" to "anthropic/claude-sonnet-5",
        "claude-fable-5-1" to "anthropic/claude-fable-5.1",
        "claude-opus-5-5" to "anthropic/claude-opus-5.5",
        "claude-sonnet-5-5" to "anthropic/claude-sonnet-5.5",
        "claude-haiku-5-5" to "anthropic/claude-haiku-5.5",
        // OpenAI
        "gpt-5" to "openai/gpt-5",
        "gpt-5-mini" to "openai/gpt-5-mini",
        "gpt-5-nano" to "openai/gpt-5-nano",
        "gpt-5.1" to "openai/gpt-5.1",
        "gpt-5.4" to "openai/gpt-5.4",
        "gpt-5.4-mini" to "openai/gpt-5.4-mini",
        "gpt-5.5" to "openai/gpt-5.5",
        "gpt-5.6-sol" to "openai/gpt-5.6-sol",
        "gpt-5.6-terra" to "openai/gpt-5.6-terra",
        "gpt-5.6-luna" to "openai/gpt-5.6-luna",
        "gpt-6-sol" to "openai/gpt-6-sol",
        "gpt-6-luna" to "openai/gpt-6-luna",
        "gpt-4.1" to "openai/gpt-4.1",
        "gpt-4.1-mini" to "openai/gpt-4.1-mini",
        "gpt-4o" to "openai/gpt-4o",
        "gpt-4o-mini" to "openai/gpt-4o-mini",
        "o3" to "openai/o3",
        "o3-mini" to "openai/o3-mini",
        // Google
        "gemini-2.5-pro" to "google/gemini-2.5-pro",
        "gemini-2.5-flash" to "google/gemini-2.5-flash",
        "gemini-2.5-flash-lite" to "google/gemini-2.5-flash-lite",
        "gemini-3.1-pro-preview" to "google/gemini-3.1-pro-preview",
        "gemini-3.8-flash" to "google/gemini-3.8-flash",
        "gemini-3.6-flash" to "google/gemini-3.6-flash",
        "gemini-3.5-flash-lite" to "google/gemini-3.5-flash-lite",
        "gemini-4-argon" to "google/gemini-4-argon",
    )

    /**
     * Bundled models OpenRouter does not serve. The on-device model never leaves the
     * device, so no route reaches it.
     */
    internal val NOT_OFFERED: Set<String> = setOf(
        "apple-foundation-models-on-device",
    )

    /** OpenRouter's slug for [model], or null when OpenRouter does not offer it. */
    fun slugFor(model: AIModel): String? = SLUGS[model.name]
}
