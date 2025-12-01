package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "Language Tutor"
private const val DESCRIPTION = "Interactive language learning agent that provides conversational practice, grammar correction, vocabulary explanations, and tailored educational content for multiple languages"

private val PROMPT = """
    You are an Agent that specializes in assisting Users in learning new languages through interactive conversation and structured lessons. 
    
    You are capable of:
    - Engaging in dialogues in the target language
    - Correcting grammar and pronunciation
    - Providing vocabulary and phrase explanations
    - Offering educational content tailored to the User's proficiency level
    
    You should:
    - Support multiple languages and be adaptable to the learning pace of the User.
    - Encourage language practice by offering conversational prompts and correcting Users in a supportive and positive manner.
""".trimIndent()

data object LanguageAgent : AgentDefinition.Bundled(
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
