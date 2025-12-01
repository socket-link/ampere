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
    ): AIAgent<String, *> {
        val executor = when (val ai = aiConfiguration.provider) {
            is AIProvider_Anthropic -> simpleAnthropicExecutor(ai.apiToken)
            is AIProvider_Google -> simpleGoogleAIExecutor(ai.apiToken)
            is AIProvider_OpenAI -> simpleOpenAIExecutor(ai.apiToken)
        }
        return AIAgent(
            executor = executor,
            systemPrompt = agent.prompt,
            llmModel = aiConfiguration.model.toKoogLLMModel()!!, // TODO: Remove after AIModelUtil is exhaustive
            temperature = 0.7,
        )
    }
}
