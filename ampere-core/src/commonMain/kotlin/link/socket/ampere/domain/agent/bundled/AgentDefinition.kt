package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.domain.agent.AgentInput
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.chat.Instructions

sealed interface AgentDefinition {

    val name: String
    val description: String
    val suggestedAIConfigurationBuilder: AIConfigurationFactory.() -> AIConfiguration

    val prompt: String
    val requiredInputs: List<AgentInput>
    val optionalInputs: List<AgentInput>

    val minimumRung: CapabilityRung?
        get() = null

    val instructions: Instructions
        get() = Instructions(
            prompt = prompt,
        )

    sealed class Bundled(
        override val name: String,
        override val description: String,
        override val suggestedAIConfigurationBuilder: AIConfigurationFactory.() -> AIConfiguration,
        override val prompt: String = "",
        override val requiredInputs: List<AgentInput> = emptyList(),
        override val optionalInputs: List<AgentInput> = emptyList(),
    ) : AgentDefinition

    abstract class Custom(
        override val name: String,
        override val description: String,
        override val suggestedAIConfigurationBuilder: (AIConfigurationFactory) -> AIConfiguration,
        override val prompt: String = "",
        override val requiredInputs: List<AgentInput> = emptyList(),
        override val optionalInputs: List<AgentInput> = emptyList(),
    ) : AgentDefinition

    /**
     * A simple custom agent definition for Spark-based agents.
     *
     * This allows creating agent definitions without subclassing,
     * useful for dynamically configured agents like SparkBasedAgent.
     */
    companion object {
        /**
         * Creates a custom agent definition with minimal configuration.
         *
         * @param minimumRung Optional capability-rung floor for this agent. When
         *   set, [AgentLLMService][link.socket.ampere.agents.domain.reasoning.AgentLLMService]
         *   threads it into the [RoutingContext][link.socket.ampere.agents.domain.routing.RoutingContext]
         *   so the relay refuses to route below it (AMPR-219). Defaults to `null`
         *   (no floor), preserving the prior behavior for existing callers.
         */
        fun Custom(
            name: String,
            description: String,
            prompt: String,
            requiredInputs: List<AgentInput> = emptyList(),
            optionalInputs: List<AgentInput> = emptyList(),
            minimumRung: CapabilityRung? = null,
            aiConfigurationBuilder: (AIConfigurationFactory) -> AIConfiguration = { it.getDefaultConfiguration() },
        ): AgentDefinition = object : Custom(
            name = name,
            description = description,
            suggestedAIConfigurationBuilder = aiConfigurationBuilder,
            prompt = prompt,
            requiredInputs = requiredInputs,
            optionalInputs = optionalInputs,
        ) {
            override val minimumRung: CapabilityRung? = minimumRung
        }
    }
}
