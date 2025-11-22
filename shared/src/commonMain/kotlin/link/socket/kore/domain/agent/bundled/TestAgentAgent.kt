package link.socket.kore.domain.agent.bundled

import link.socket.kore.domain.ai.model.AIModel_Claude
import link.socket.kore.domain.ai.model.AIModel_Gemini
import link.socket.kore.domain.ai.model.AIModel_OpenAI

private const val NAME: String = "Empty System Prompt"
private const val DESCRIPTION = "Flexible testing agent with customizable system prompts for experimental AI interactions and prompt testing scenarios"

data class TestAgentAgent(
    override val prompt: String,
) : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = prompt,
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Claude.Opus_4_1,
            aiConfiguration(AIModel_OpenAI.GPT_4_1),
            aiConfiguration(AIModel_Gemini.Pro_2_5),
        )
    },
)
