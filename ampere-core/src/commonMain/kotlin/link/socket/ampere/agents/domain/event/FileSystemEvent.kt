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
        override val urgency: Urgency = Urgency.MEDIUM,
        val filePath: String,
        val fileName: String,
        val fileExtension: String?,
        val workspacePath: String,
        val relativePath: String,
    ) : FileSystemEvent {
        override val eventType: EventType = EVENT_TYPE

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
        override val urgency: Urgency = Urgency.LOW,
        val filePath: String,
        val fileName: String,
        val fileExtension: String?,
        val workspacePath: String,
        val relativePath: String,
    ) : FileSystemEvent {
        override val eventType: EventType = EVENT_TYPE

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
        override val urgency: Urgency = Urgency.LOW,
        val filePath: String,
        val fileName: String,
        val workspacePath: String,
        val relativePath: String,
    ) : FileSystemEvent {
        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "FileDeleted"
        }
    }
}
