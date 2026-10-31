package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes a ticket into a worktree the way the operator's tooling already does
 * (AMPR-308, Context §4): the body and synced comments as one markdown file at
 * `.context/attachments/<source>-<uuid>/[<SOURCE>]-<identifier>.md`.
 *
 * Matching the existing convention is the point. An agent dispatched by hand reads
 * its ticket from exactly that path (verified in the AMPR-288 ethnography), so an
 * agent dispatched by the Switchboard gets the same input and the same repository
 * contract — `AGENTS.md` decides which concept cells it reads, never the dispatcher
 * (operator answer 6).
 *
 * The file is staged and renamed into place, so an agent never reads half a ticket.
 */
class TicketContext(private val conventions: DispatchConventions) {

    /** The context file's path relative to the worktree root. */
    fun relativePath(ticket: DispatchTicket): String {
        val prefix = conventions.attachmentPrefix
        val key = (ticket.uuid ?: ticket.identifier).replace(UNSAFE, "-")
        val name = ticket.identifier.replace(UNSAFE, "-")
        return "$CONTEXT_DIRECTORY/$prefix-$key/[${prefix.uppercase()}]-$name.md"
    }

    /** Write [ticket] and its [comments] into [worktree]. Returns the file written. */
    fun materialize(worktree: Path, ticket: DispatchTicket, comments: List<TicketComment>?): Path {
        val target = worktree.resolve(relativePath(ticket)).normalize()
        require(target.startsWith(worktree.normalize())) { "the context path escapes the worktree: $target" }
        Files.createDirectories(target.parent)
        val staged = Files.createTempFile(target.parent, ".ticket-", ".tmp")
        try {
            Files.writeString(staged, render(ticket, comments))
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
        return target
    }

    /**
     * The file's contents.
     *
     * @param comments Null when they could not be read — said so in the file rather
     *   than written as an empty thread, so the agent does not take "no comments"
     *   for a fact.
     */
    fun render(ticket: DispatchTicket, comments: List<TicketComment>?): String = buildString {
        appendLine("# ${ticket.identifier}: ${ticket.title}")
        appendLine()
        ticket.url?.let {
            appendLine("**URL:** $it")
            appendLine()
        }
        appendLine("**Status:** ${ticket.statusName}")
        if (ticket.labels.isNotEmpty()) appendLine("**Labels:** ${ticket.labels.sorted().joinToString()}")
        appendLine()
        appendLine("## Description")
        appendLine()
        appendLine(ticket.description?.trimEnd() ?: "_No description._")
        appendLine()
        appendLine("## Comments")
        when {
            comments == null -> {
                appendLine()
                appendLine("_The comments could not be read when this file was written._")
            }

            comments.isEmpty() -> {
                appendLine()
                appendLine("_No comments._")
            }

            else -> comments.forEach { comment ->
                appendLine()
                appendLine("### ${comment.author ?: "unknown"} - ${comment.createdAt}")
                appendLine()
                appendLine(comment.body.trimEnd())
            }
        }
    }

    companion object {

        /** The gitignored directory the operator's tooling writes ticket context into. */
        const val CONTEXT_DIRECTORY: String = ".context/attachments"

        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
    }
}
