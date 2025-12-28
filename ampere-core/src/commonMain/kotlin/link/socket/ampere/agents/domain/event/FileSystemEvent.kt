package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency

/**
 * Events related to file system changes in workspace directories.
 *
 * These events enable receptor-based monitoring of file changes,
 * allowing agents to react to new files, modifications, and deletions.
 */
@Serializable
sealed interface FileSystemEvent : Event {

    val filePath: String
    val fileName: String
    val fileExtension: String?
    val workspacePath: String
    val relativePath: String

    /**
     * Emitted when a new file is created in a monitored workspace directory.
     *
     * This event can trigger various agent behaviors, such as:
     * - Parsing markdown feature specifications
     * - Processing new configuration files
     * - Detecting new test cases or documentation
     */
    @Serializable
    data class FileCreated(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val filePath: String,
        override val fileName: String,
        override val fileExtension: String?,
        override val workspacePath: String,
        override val relativePath: String,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : FileSystemEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("File created: $relativePath")
            fileExtension?.let { append(" (.$it)") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "FileCreated"
        }
    }

    /**
     * Emitted when a file is modified in a monitored workspace directory.
     */
    @Serializable
    data class FileModified(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val filePath: String,
        override val fileName: String,
        override val fileExtension: String?,
        override val workspacePath: String,
        override val relativePath: String,
        override val urgency: Urgency = Urgency.LOW,
    ) : FileSystemEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("File modified: $relativePath")
            fileExtension?.let { append(" (.$it)") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "FileModified"
        }
    }

    /**
     * Emitted when a file is deleted from a monitored workspace directory.
     */
    @Serializable
    data class FileDeleted(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val filePath: String,
        override val fileName: String,
        override val fileExtension: String?,
        override val workspacePath: String,
        override val relativePath: String,
        override val urgency: Urgency = Urgency.LOW,
    ) : FileSystemEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("File deleted: $relativePath")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "FileDeleted"
        }
    }
}
