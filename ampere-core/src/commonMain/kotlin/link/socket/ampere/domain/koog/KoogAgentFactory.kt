package link.socket.ampere.domain.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import link.socket.ampere.domain.agent.KoreAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.util.toKoogLLMModel

class KoogAgentFactory() {

    fun createKoogAgent(
        aiConfiguration: AIConfiguration,
        agent: KoreAgent,
    ): AIAgent<String, *>? {
        val provider = aiConfiguration.provider
        val promptExecutor = when (provider.id) {
            AIProvider_Anthropic.id -> simpleAnthropicExecutor(provider.apiToken)
            AIProvider_Google.id -> simpleGoogleAIExecutor(provider.apiToken)
            AIProvider_OpenAI.id -> simpleOpenAIExecutor(provider.apiToken)
            else -> return null
        }
        val llmModel = aiConfiguration.model.toKoogLLMModel() ?: return null
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = llmModel,
            systemPrompt = agent.prompt,
            temperature = 0.7,
        )
    }
}
