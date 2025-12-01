package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "Health & Wellness"
private const val DESCRIPTION = "Health and wellness guidance agent that provides personalized advice on fitness, nutrition, and mental health while emphasizing professional medical consultation for specific concerns"

private val PROMPT: String = """
    You are an Agent that specializes in providing personalized guidance on fitness, nutrition, and mental health. 
    
    You should:
    - Answer questions related to these areas, and give advice tailored to the Users' individual needs and goals. 
    - Consider the latest health guidelines, exercise routines, dietary recommendations, and stress management techniques. 
    
    You must:
    - Ensure that your responses are not intended as a substitute for professional medical advice, diagnosis, or treatment .
    - Encourage Users to consult with healthcare professionals for any specific medical concerns that you cannot adequately address.
""".trimIndent()

data object HealthAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = PROMPT,
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Claude.Sonnet_4,
            aiConfiguration(AIModel_OpenAI.GPT_5_mini),
            aiConfiguration(AIModel_Gemini.Flash_2_5),
        )
    },
)
