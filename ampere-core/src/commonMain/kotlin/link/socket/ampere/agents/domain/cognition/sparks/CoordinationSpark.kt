package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId

/**
 * A Spark that focuses on explicit coordination and handoffs between agents.
 *
 * CoordinationSparks narrow the agent's focus to collaboration mechanics:
 * - Clear ownership and task boundaries
 * - Explicit handoff summaries
 * - Visible, event-driven state changes
 */
@Serializable
sealed class CoordinationSpark : Spark {

    /**
     * Coordination context for handoffs between agents.
     */
    @Serializable
    @SerialName("CoordinationSpark.Handoff")
    data object Handoff : CoordinationSpark() {

        override val name: String = "Coordination:Handoff"

        override val promptContribution: String = """
## Coordination: Handoff

You are specializing in **agent-to-agent handoffs and coordination**.

### Goals
- Make ownership explicit (who owns the task now).
- Convert ambiguity into crisp handoff steps.
- Emit and reference coordination events when possible (TaskCreated, TaskAssigned, StatusChanged).
- Ask for missing information instead of guessing.

### Operating Guidelines
- Summarize context, decisions, open questions, and next steps during handoffs.
- Keep handoff notes concise and structured.
- Identify a single primary owner when multiple agents are involved.
- Prefer observable state changes over implicit assumptions.
        """.trimIndent()

        override val allowedTools: Set<ToolId>? = null // Inherit from role/tool context

        override val fileAccessScope: FileAccessScope? = null // No additional file restrictions
    }
}
