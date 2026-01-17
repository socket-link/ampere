package link.socket.ampere.dsl.agent

import link.socket.ampere.domain.agent.bundled.APIDesignAgent
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.agent.bundled.DelegateTasksAgent
import link.socket.ampere.domain.agent.bundled.DocumentationAgent
import link.socket.ampere.domain.agent.bundled.QATestingAgent
import link.socket.ampere.domain.agent.bundled.SecurityReviewAgent
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent

/**
 * Capabilities that agent roles can provide.
 */
enum class Capability {
    CODE_WRITING,
    CODE_REVIEW,
    TESTING,
    PLANNING,
    DELEGATION,
    DOCUMENTATION,
    API_DESIGN,
    SECURITY_REVIEW,
}

/**
 * Represents a role that can be assigned to an agent in a team.
 *
 * Roles map to AgentDefinitions but provide a higher-level abstraction
 * for team composition.
 *
 * Example:
 * ```kotlin
 * val team = AgentTeam.create {
 *     agent(ProductManager) { personality { directness = 0.8 } }
 *     agent(Engineer) { personality { creativity = 0.7 } }
 *     agent(QATester)
 * }
 * ```
 */
sealed class AgentRole(
    val name: String,
    val description: String,
    val capabilities: Set<Capability>,
) {
    /**
     * Get the underlying AgentDefinition for this role.
     * Personality traits are injected into the prompt.
     */
    abstract fun toAgentDefinition(personality: Personality?): AgentDefinition
}

/**
 * Product Manager role - coordinates work and makes decisions.
 */
data object ProductManager : AgentRole(
    name = "Product Manager",
    description = "Coordinates work, breaks down tasks, and makes product decisions",
    capabilities = setOf(
        Capability.PLANNING,
        Capability.DELEGATION,
    ),
) {
    override fun toAgentDefinition(personality: Personality?): AgentDefinition {
        if (personality == null) return DelegateTasksAgent

        return object : AgentDefinition.Custom(
            name = "Product Manager",
            description = description,
            suggestedAIConfigurationBuilder = DelegateTasksAgent.suggestedAIConfigurationBuilder,
            prompt = buildPrompt(DelegateTasksAgent.prompt, personality),
        ) {}
    }

    private fun buildPrompt(basePrompt: String, personality: Personality): String = buildString {
        appendLine(basePrompt)
        appendLine()
        appendLine("## Communication Style")
        appendLine(
            "- Directness: ${formatTrait(personality.directness)} - ${directnessGuidance(personality.directness)}",
        )
        appendLine(
            "- Thoroughness: ${formatTrait(personality.thoroughness)} - ${thoroughnessGuidance(personality.thoroughness)}",
        )
        appendLine("- Formality: ${formatTrait(personality.formality)} - ${formalityGuidance(personality.formality)}")
    }
}

/**
 * Engineer role - writes and reviews code.
 */
data object Engineer : AgentRole(
    name = "Engineer",
    description = "Writes production-quality code and implements features",
    capabilities = setOf(
        Capability.CODE_WRITING,
        Capability.CODE_REVIEW,
    ),
) {
    override fun toAgentDefinition(personality: Personality?): AgentDefinition {
        if (personality == null) return WriteCodeAgent

        return object : AgentDefinition.Custom(
            name = "Engineer",
            description = description,
            suggestedAIConfigurationBuilder = WriteCodeAgent.suggestedAIConfigurationBuilder,
            prompt = buildPrompt(WriteCodeAgent.prompt, personality),
        ) {}
    }

    private fun buildPrompt(basePrompt: String, personality: Personality): String = buildString {
        appendLine(basePrompt)
        appendLine()
        appendLine("## Coding Style")
        appendLine(
            "- Creativity: ${formatTrait(personality.creativity)} - ${creativityGuidance(personality.creativity)}",
        )
        appendLine(
            "- Thoroughness: ${formatTrait(personality.thoroughness)} - ${thoroughnessGuidance(personality.thoroughness)}",
        )
        appendLine(
            "- Risk Tolerance: ${formatTrait(personality.riskTolerance)} - ${riskGuidance(personality.riskTolerance)}",
        )
    }
}

/**
 * QA Tester role - tests code and finds bugs.
 */
data object QATester : AgentRole(
    name = "QA Tester",
    description = "Creates comprehensive test suites and identifies edge cases",
    capabilities = setOf(
        Capability.TESTING,
        Capability.CODE_REVIEW,
    ),
) {
    override fun toAgentDefinition(personality: Personality?): AgentDefinition {
        // QA personality is standardized - testing should be thorough by default
        return QATestingAgent
    }
}

/**
 * Architect role - designs APIs and system architecture.
 */
data object Architect : AgentRole(
    name = "Architect",
    description = "Designs system architecture and APIs",
    capabilities = setOf(
        Capability.API_DESIGN,
        Capability.PLANNING,
    ),
) {
    override fun toAgentDefinition(personality: Personality?): AgentDefinition {
        return APIDesignAgent
    }
}

/**
 * Security Reviewer role - reviews code for security issues.
 */
data object SecurityReviewer : AgentRole(
    name = "Security Reviewer",
    description = "Reviews code for security vulnerabilities and best practices",
    capabilities = setOf(
        Capability.SECURITY_REVIEW,
        Capability.CODE_REVIEW,
    ),
) {
    override fun toAgentDefinition(personality: Personality?): AgentDefinition {
        return SecurityReviewAgent
    }
}

/**
 * Technical Writer role - writes documentation.
 */
data object TechnicalWriter : AgentRole(
    name = "Technical Writer",
    description = "Creates comprehensive documentation",
    capabilities = setOf(
        Capability.DOCUMENTATION,
    ),
) {
    override fun toAgentDefinition(personality: Personality?): AgentDefinition {
        return DocumentationAgent
    }
}

// Personality guidance helpers

private fun formatTrait(value: Double): String =
    "${(value * 100).toInt()}%"

private fun directnessGuidance(directness: Double): String = when {
    directness < 0.3 -> "be diplomatic and soften feedback"
    directness < 0.7 -> "balance directness with tact"
    else -> "be very direct and straightforward"
}

private fun creativityGuidance(creativity: Double): String = when {
    creativity < 0.3 -> "prefer conventional, well-established patterns"
    creativity < 0.7 -> "balance innovation with proven approaches"
    else -> "explore creative solutions and novel patterns"
}

private fun thoroughnessGuidance(thoroughness: Double): String = when {
    thoroughness < 0.3 -> "be concise and focus on essentials"
    thoroughness < 0.7 -> "balance detail with brevity"
    else -> "be thorough and comprehensive"
}

private fun formalityGuidance(formality: Double): String = when {
    formality < 0.3 -> "use casual, conversational tone"
    formality < 0.7 -> "use professional but approachable tone"
    else -> "use formal, professional language"
}

private fun riskGuidance(riskTolerance: Double): String = when {
    riskTolerance < 0.3 -> "prefer safe, proven approaches"
    riskTolerance < 0.7 -> "take calculated risks when appropriate"
    else -> "embrace innovative approaches even with uncertainty"
}
