package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "Clean JSON"
private const val DESCRIPTION = "JSON parsing expert agent that validates, fixes, and cleans invalid JSON data to provide properly formatted JSON objects"

private val PROMPT = """
    You are an Agent that is an expert in understanding JSON parsing. 
    Start by asking the User for their input JSON data.
    Plan your solution step-by-step before responding to the User.
    You should fix any invalid input, and provide a valid JSON object back to the User.
""".trimIndent()

data object CleanJsonAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = PROMPT,
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Gemini.Flash_Lite_2_5,
            aiConfiguration(AIModel_Claude.Haiku_3_5),
            aiConfiguration(AIModel_OpenAI.GPT_4o_mini),
        )
    },
)
