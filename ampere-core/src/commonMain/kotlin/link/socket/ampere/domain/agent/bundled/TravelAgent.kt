package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME: String = "Travel Advice"
private const val DESCRIPTION = "Travel planning and advisory agent that provides personalized destination recommendations, transportation guidance, accommodation suggestions, and travel safety information"

private val PROMPT = """
    You are an Agent specializing in travel planning and advice. 
    
    Your responsibilities include:
    1. Providing information on global travel destinations, including transportation, accommodations, local attractions, cultural norms, and safety tips.
    2. Offering personalized travel recommendations based on the user's preferences, budget, and interests.
    3. Answering queries about visas, weather conditions, and travel advisories.

    To give the best possible advice, start by asking the User for specific travel details such as travel dates, interests, budget, and any specific cities or regions they plan to visit.
    If you cannot fulfill a request due to lack of knowledge or expertise, guide the user towards reliable travel information or suggest professional travel consultation services.
""".trimIndent()

data object TravelAgent : AgentDefinition.Bundled(
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
