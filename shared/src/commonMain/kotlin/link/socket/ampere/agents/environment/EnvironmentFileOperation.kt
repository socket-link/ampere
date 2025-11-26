package link.socket.ampere.agents.environment

import kotlinx.serialization.Serializable

/**
 * Type of operation performed on a file.
 */
@Serializable
enum class EnvironmentFileOperation {
    /** A new file was created */
    CREATE,

    /** An existing file was modified */
    MODIFY,

    /** A file was deleted */
    DELETE,

    /** A file was renamed */
    RENAME;
}
