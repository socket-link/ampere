package link.socket.ampere.demo

/**
 * Golden output for the demo - pre-validated ObservabilitySpark.kt content.
 *
 * This is used as a fallback when the LLM fails to generate valid code,
 * ensuring the demo can complete successfully in CI/offline environments.
 *
 * The golden output is based on the existing Spark patterns in the codebase
 * (e.g., PhaseSpark.kt) and meets all requirements specified in the ticket.
 */
object GoldenOutput {

    /**
     * The target file path for ObservabilitySpark.kt.
     */
    const val OBSERVABILITY_SPARK_PATH = "ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks/ObservabilitySpark.kt"

    /**
     * Pre-validated ObservabilitySpark.kt content.
     *
     * This code:
     * - Follows the existing Spark pattern (sealed class hierarchy)
     * - Uses @Serializable and @SerialName annotations for polymorphic serialization
     * - Sets allowedTools and fileAccessScope to null (non-restrictive)
     * - Includes a Verbose data object variant
     * - Provides guidance on emitting events and reporting progress
     */
    val OBSERVABILITY_SPARK_CONTENT = """
package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.execution.ToolId
import link.socket.ampere.domain.file.FileAccessScope

/**
 * Spark that provides guidance about visibility, monitoring, and progress reporting.
 *
 * This Spark encourages agents to emit frequent status updates and make their
 * work visible through the event system, enabling better monitoring and debugging.
 */
@Serializable
sealed class ObservabilitySpark : Spark {

    override val allowedTools: Set<ToolId>? = null
    override val fileAccessScope: FileAccessScope? = null

    /**
     * Verbose observability mode - encourages detailed status updates and progress emission.
     *
     * When active, agents should:
     * - Emit events at the start and end of each cognitive phase
     * - Report intermediate progress during long-running operations
     * - Include relevant context in status updates
     * - Log decisions and reasoning for debugging
     */
    @Serializable
    @SerialName("ObservabilitySpark.Verbose")
    data object Verbose : ObservabilitySpark() {
        override val promptContribution: String = buildString {
            appendLine("## Observability Guidelines")
            appendLine()
            appendLine("You are operating in VERBOSE observability mode. To ensure your work is visible and monitorable:")
            appendLine()
            appendLine("1. **Emit Progress Events**: Report status at the start and end of each major operation.")
            appendLine("2. **Include Context**: Add relevant details (file names, task IDs, etc.) to status updates.")
            appendLine("3. **Log Decisions**: Explain key decisions and reasoning for later debugging.")
            appendLine("4. **Report Metrics**: When possible, include counts, durations, or other measurable outcomes.")
            appendLine("5. **Signal State Changes**: Notify when transitioning between cognitive phases.")
            appendLine()
            appendLine("This visibility helps operators monitor agent progress and debug issues effectively.")
        }
    }
}
""".trimIndent()

    /**
     * Writes the golden ObservabilitySpark.kt to the specified output directory.
     *
     * @param outputDir The directory to write the file to
     * @return The full path to the written file
     */
    fun writeObservabilitySpark(outputDir: java.io.File): String {
        val file = java.io.File(outputDir, OBSERVABILITY_SPARK_PATH)
        file.parentFile?.mkdirs()
        file.writeText(OBSERVABILITY_SPARK_CONTENT)
        return file.absolutePath
    }
}
