package link.socket.ampere.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root configuration for an Ampere environment.
 *
 * Example YAML:
 * ```yaml
 * ai:
 *   provider: anthropic
 *   model: sonnet-4
 *   backups:
 *     - provider: openai
 *       model: gpt-4.1
 *
 * team:
 *   - role: product-manager
 *     personality:
 *       directness: 0.8
 *   - role: engineer
 *     personality:
 *       creativity: 0.7
 *   - role: qa-tester
 *
 * goal: "Build a user authentication system"
 * ```
 */
@Serializable
data class AmpereConfig(
    /** AI provider configuration */
    val ai: AIProviderConfig,

    /** Team composition - list of agents with their roles and personalities */
    val team: List<AgentConfig>,

    /** Optional initial goal for the team */
    val goal: String? = null,
)

/**
 * AI provider configuration.
 *
 * Supported providers: anthropic, openai, gemini
 *
 * Models are specified using human-friendly names:
 * - Anthropic: opus-4.5, opus-4.1, opus-4, sonnet-4.5, sonnet-4, sonnet-3.7, haiku-4.5, haiku-3.5, haiku-3
 * - OpenAI: gpt-5.1, gpt-5, gpt-5-mini, gpt-5-nano, gpt-4.1, gpt-4.1-mini, gpt-4o, gpt-4o-mini, o4-mini, o3, o3-mini
 * - Gemini: pro-3, pro-2.5, flash-2.5, flash-2.5-lite, flash-2, flash-2-lite
 */
@Serializable
data class AIProviderConfig(
    /** Provider name: anthropic, openai, or gemini */
    val provider: String,

    /** Model name (provider-specific) */
    val model: String,

    /** Optional fallback providers if primary fails */
    val backups: List<AIProviderConfig> = emptyList(),
)

/**
 * Agent configuration within a team.
 *
 * Supported roles:
 * - product-manager: Coordinates work and makes product decisions
 * - engineer: Writes production-quality code
 * - qa-tester: Tests code and finds bugs
 * - architect: Designs system architecture and APIs
 * - security-reviewer: Reviews code for security issues
 * - technical-writer: Creates documentation
 */
@Serializable
data class AgentConfig(
    /** Agent role identifier */
    val role: String,

    /** Optional personality configuration (uses defaults if not specified) */
    val personality: PersonalityConfig? = null,
)

/**
 * Personality traits for an agent.
 * All values range from 0.0 to 1.0.
 *
 * Example:
 * ```yaml
 * personality:
 *   directness: 0.8        # 0=diplomatic, 1=very direct
 *   creativity: 0.7        # 0=conventional, 1=creative
 *   thoroughness: 0.6      # 0=concise, 1=thorough
 *   formality: 0.5         # 0=casual, 1=formal
 *   risk-tolerance: 0.3    # 0=conservative, 1=risk-taking
 * ```
 */
@Serializable
data class PersonalityConfig(
    /** How direct vs diplomatic (0=diplomatic, 1=very direct) */
    val directness: Double = 0.5,

    /** How creative vs conventional (0=conventional, 1=creative) */
    val creativity: Double = 0.5,

    /** How thorough vs concise (0=concise, 1=thorough) */
    val thoroughness: Double = 0.5,

    /** How formal vs casual (0=casual, 1=formal) */
    val formality: Double = 0.5,

    /** How risk-tolerant vs conservative (0=conservative, 1=risk-taking) */
    @SerialName("risk-tolerance")
    val riskTolerance: Double = 0.3,
)
