package link.socket.kore.io

import androidx.compose.ui.text.capitalize
import androidx.compose.ui.text.intl.Locale
import link.socket.kore.domain.chat.Conversation
import link.socket.kore.util.toSnakeCase

// Constants for export folder and file extension.
private const val EXPORT_FOLDER = "Ampere-Test"
private const val FILE_EXTENSION = ".chatlog"

/**
 * Extension function to export a Conversation to a file.
 * The file will be saved in the EXPORT_FOLDER directory with a filename derived from the conversation's title.
 */
fun Conversation.exportToFile() {
    val filename = getFilename()
    val content = asString()
    createFile(EXPORT_FOLDER, filename, content)
}

/**
 * Generates a filename for the conversation export, converting the title to snake_case format.
 *
 * @return the filename as a String with FILE_EXTENSION appended.
 */
fun Conversation.getFilename(): String =
    // Uses the title of the conversation for naming the file, converted to snake case.
    title.toSnakeCase().plus(FILE_EXTENSION)

/**
 * Converts the conversation to a String format suitable for exporting.
 * The format includes the agent's name and each chat message with the sender's role.
 *
 * @return the formatted conversation as a String.
 */
fun Conversation.asString(): String =
    buildString {
        appendLine("---") // Start of the conversation log.
        appendLine("agent: ${agent.name}") // Log the agent's name.
        appendLine("---")

        val chats = getChats()

        chats.forEachIndexed { index, chat ->
            val chatRole = chat.role.role.capitalize(Locale.current) // Capitalize the role.
            val chatLine = "\n" + "[$chatRole]" + "\n\n" + chat.chatMessage.content
            appendLine(chatLine)

            if (index != chats.size - 1) {
                appendLine("\n---") // Separator between chat messages.
            }
        }
    }
