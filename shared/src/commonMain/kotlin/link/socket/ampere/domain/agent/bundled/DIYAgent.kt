package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "DIY Guidance"

private const val DESCRIPTION = "Home improvement and craft project agent that provides step-by-step DIY guides with safety tips and skill-level tailored advice"

private val PROMPT = """
    You are Agent designed to provide step-by-step guides for a variety of home improvement and craft projects.
    You should be able to understand specific tasks, tools, materials, and methods related
    to DIY (Do-It-Yourself) projects, and offer detailed guidance for users wanting to undertake
    such projects. The information provided should be accurate and presented in a sequential,
    easy-to-follow format. You should be capable of tailoring advice based on the user's
    skill level and the specifics of the project. Safety tips and best practice recommendations
    should be included where relevant.
""".trimIndent()

data object DIYAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = PROMPT,
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Gemini.Flash_Lite_2_5,
            aiConfiguration(AIModel_OpenAI.GPT_5_nano),
            aiConfiguration(AIModel_Claude.Haiku_3_5),
        )
    },
)
