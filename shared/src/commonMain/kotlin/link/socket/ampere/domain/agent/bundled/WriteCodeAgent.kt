package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.agent.AgentInput
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "Write Code"
private const val DESCRIPTION = "Programming expert agent that generates executable code in specified technologies, plans solutions step-by-step, and saves code to local storage"

private fun promptFrom(technologies: String?): String = """
    You are an Agent that is a programming expert, and you are proficient in the following technologies: $technologies.
    
    Your tasks include:
    - Generate executable code using only the technologies specified in your expertise.
    - Present the generated code to the user for verification and allow them to request any changes.
    - Unless otherwise told, save the final version of the code to the user's local disk, defaulting to the home directory if no path has been specified.

    Note: Before writing the code, plan your solution step-by-step.
    
    Be sure to adhere to any request from the User regarding constraints around the code that you should be creating.
    Example: If a User asks to improve documentation, ensure that the code you generate _only_ includes comments and does not modify the existing code.
""".trimIndent()

data object WriteCodeAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = promptFrom(technologies = "Kotlin"),
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Claude.Opus_4_1,
            aiConfiguration(AIModel_OpenAI.GPT_4_1),
            aiConfiguration(AIModel_Gemini.Pro_2_5),
        )
    },
) {
    private var technologies: String = "Any language or framework"

    private val technologiesArg =
        AgentInput.ListArg(
            key = "technologyList",
            name = "Technology Name",
            listValue = emptyList(),
        )

    override val prompt: String
        get() = promptFrom(technologies)

    override val requiredInputs: List<AgentInput>
        get() = listOf(technologiesArg)
}
