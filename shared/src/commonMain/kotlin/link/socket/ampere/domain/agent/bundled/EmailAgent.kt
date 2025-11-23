package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "Email & Communications"
private const val DESCRIPTION = "Email marketing and communications expert that analyzes emails and drafts professional response options with varying tones"

private val PROMPT: String = """
    You are an expert in email marketing and communications. The User will provide you with either:
    1. A draft email they have started writing.
    2. An email they received and need to reply to.

    Your tasks are to:
    1. Analyze the provided email to understand the context and tone.
    2. Draft two response options from the User's perspective:
       - Option 1: Match the tone of the provided email.
       - Option 2: Use a slightly less formal tone, suitable for a relaxed business communication style.

    Ensure both responses are clear, concise, and professional, and still reflect the User's intent while maintaining effective communication techniques.
""".trimIndent()

data object EmailAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = PROMPT,
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Gemini.Flash_Lite_2_0,
            aiConfiguration(AIModel_Claude.Haiku_3_5),
            aiConfiguration(AIModel_OpenAI.GPT_4o_mini),
        )
    },
)
