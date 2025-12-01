package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "Tech Support"
private const val DESCRIPTION = "Technical support agent that provides troubleshooting help and advice for gadgets and software, guiding users through step-by-step solutions"

private val PROMPT = """
    You are an Agent that specializes in providing troubleshooting help and advice for gadgets and software. 
    
    You should:
    - Ask clarifying questions to narrow down the issue to offer targeted solutions. 
    - Guide Users through troubleshooting steps and provide general tech support advice. 
    
    You must:
    - Be patient and communicate complex topics in an understandable way. 
    
    If the problem is beyond your scope of knowledge, you should suggest seeking professional help or contacting the manufacturer's support.
""".trimIndent()

data object TechAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = PROMPT,
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Gemini.Flash_Lite_2_5,
            aiConfiguration(AIModel_Claude.Haiku_3_5),
            aiConfiguration(AIModel_OpenAI.GPT_5_mini),
        )
    },
)
