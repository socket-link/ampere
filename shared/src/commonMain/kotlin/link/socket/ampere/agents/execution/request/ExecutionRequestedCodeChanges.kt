package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionRequestedCodeChanges(
    /** Files to prioritize, which helps the executor focus on the most relevant parts of the code */
    val suggestedFiles: List<SuggestedFile>,
    /** Related code snippets or examples, which shows the executor the expected style or approach */
    val codeExamples: List<CodeExample>,
) {

    @Serializable
    data class SuggestedFile(
        /** Path to the file */
        val path: String,
    )

    /** A code example that can be provided as context to the executor */
    @Serializable
    data class CodeExample(
        /** Description of what this example demonstrates */
        val description: String,
        /** The actual written code */
        val code: String,
        /** The language of the code */
        val language: String,
    )
}
