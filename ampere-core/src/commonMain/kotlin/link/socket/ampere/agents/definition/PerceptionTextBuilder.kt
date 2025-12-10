package link.socket.ampere.agents.definition

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.tickets.Ticket

/**
 * DSL for building perception text for LLM consumption.
 *
 * Provides a clean, declarative way to build structured perception text
 * without repetitive appendLine calls.
 *
 * Example usage:
 * ```kotlin
 * perceptionText {
 *     header("PM Agent Perception State")
 *     timestamp()
 *
 *     section("Blocked Tickets") {
 *         tickets.forEach { ticket ->
 *             ticket(ticket)
 *         }
 *     }
 * }
 * ```
 */
@DslMarker
annotation class PerceptionDsl

@PerceptionDsl
fun <S : AgentState> perception(
    state: S,
    block: PerceptionTextBuilder.() -> Unit,
): Perception<S> =
    Perception(
        currentState = state,
        ideas = listOf(
            Idea(
                name = perceptionText(block),
            ),
        )
    )

/**
 * Build perception text using DSL.
 */
@PerceptionDsl
fun perceptionText(block: PerceptionTextBuilder.() -> Unit): String {
    val builder = PerceptionTextBuilder()
    builder.block()
    return builder.build()
}

/**
 * Builder for perception text.
 */
@PerceptionDsl
class PerceptionTextBuilder {
    private val content = StringBuilder()

    /**
     * Add a header line with === markers.
     */
    fun header(title: String) {
        content.appendLine("=== $title ===")
    }

    /**
     * Add the current timestamp.
     */
    fun timestamp() {
        content.appendLine("Generated at: ${Clock.System.now()}")
        content.appendLine()
    }

    /**
     * Add a section with a title and content.
     */
    fun section(title: String, block: SectionBuilder.() -> Unit = {}) {
        if (title.isNotEmpty()) {
            content.appendLine("=== $title ===")
        }
        val sectionBuilder = SectionBuilder()
        sectionBuilder.block()
        content.append(sectionBuilder.build())
        content.appendLine()
    }

    /**
     * Add a section only if the condition is true.
     */
    fun sectionIf(condition: Boolean, title: String, block: SectionBuilder.() -> Unit) {
        if (condition) {
            section(title, block)
        }
    }

    /**
     * Add raw text content.
     */
    fun text(content: String) {
        this.content.append(content)
    }

    /**
     * Add a blank line.
     */
    fun blankLine() {
        content.appendLine()
    }

    /**
     * Build the final perception text.
     */
    fun build(): String = content.toString()
}

/**
 * Builder for section content.
 */
@PerceptionDsl
class SectionBuilder {
    private val content = StringBuilder()

    /**
     * Add a ticket with formatted details.
     */
    fun ticket(ticket: Ticket) {
        content.appendLine("  - [${ticket.priority}] ${ticket.title}")
        content.appendLine("    ID: ${ticket.id}")
        if (ticket.dueDate != null) {
            content.appendLine("    Due: ${ticket.dueDate}")
        }
        content.appendLine("    Status: ${ticket.status}")
        content.appendLine("    Type: ${ticket.type}")
        if (ticket.assignedAgentId != null) {
            content.appendLine("    Assigned to: ${ticket.assignedAgentId}")
        } else {
            content.appendLine("    Assigned to: UNASSIGNED")
        }
    }

    /**
     * Add a key-value pair.
     */
    fun field(key: String, value: Any?) {
        content.appendLine("  $key: $value")
    }

    /**
     * Add a bullet point item.
     */
    fun item(text: String) {
        content.appendLine("  - $text")
    }

    /**
     * Add a warning item.
     */
    fun warning(text: String) {
        content.appendLine("  ⚠ $text")
    }

    /**
     * Add raw text.
     */
    fun text(text: String) {
        content.append(text)
    }

    /**
     * Add a line.
     */
    fun line(text: String) {
        content.appendLine(text)
    }

    /**
     * Build the section content.
     */
    fun build(): String = content.toString()
}
