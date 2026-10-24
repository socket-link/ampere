package link.socket.ampere.agents.execution.executor.acp

import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.priorResultsSection
import link.socket.ampere.agents.execution.request.ExecutionRequest

/**
 * The one text block a harness is handed for a plan step (AMPR-492, AMPR-444 D7).
 *
 * The ticket the step belongs to, the step itself, where the workspace is, what the spark's
 * file access scope permits — as prose, so a cooperative agent does not have to discover the
 * fence by being refused — the results of the steps before this one through the one renderer
 * every strategy uses (`priorResultsSection`, AMPR-408), and the standing rules. The scope
 * text is advisory; `HarnessFence` enforces.
 */
object HarnessPrompt {

    fun render(
        request: ExecutionRequest<*>,
        workspace: ExecutionWorkspace,
        scope: FileAccessScope?,
    ): String = buildString {
        val context = request.context
        appendLine("# Task")
        appendLine(context.ticket.title)
        val description = context.ticket.description.trim()
        if (description.isNotBlank() && description != context.instructions.trim()) {
            appendLine()
            appendLine(description)
        }
        appendLine()
        appendLine("# This step")
        appendLine(context.instructions.trim())
        appendLine()
        appendLine("# Workspace")
        appendLine("Root: ${workspace.baseDirectory}")
        appendLine("Paths below are relative to it. Do not read or write anything outside it.")
        appendLine()
        appendLine("# File access")
        appendLine(describeScope(scope))
        val prior = priorResultsSection(request.priorResults)
        if (prior.isNotBlank()) {
            appendLine()
            append(prior.trimEnd())
            appendLine()
        }
        appendLine()
        appendLine("# Rules")
        appendLine("- Make the change directly in the working tree. Do not commit, stash, push or branch.")
        appendLine("- Touch only files the file access rules permit; a request outside them will be refused.")
        appendLine("- When you are done, reply with a short summary of what you changed and why.")
    }

    /** The scope as rules a model can follow. */
    fun describeScope(scope: FileAccessScope?): String {
        if (scope == null) return "Any file inside the workspace may be read or written."
        return buildString {
            appendLine("- May read: ${scope.readPatterns.describePatterns()}")
            appendLine("- May write: ${scope.writePatterns.describePatterns()}")
            if (scope.forbiddenPatterns.isNotEmpty()) {
                appendLine("- Never touch: ${scope.forbiddenPatterns.sorted().joinToString(", ")}")
            }
        }.trimEnd()
    }

    private fun Set<String>.describePatterns(): String = when {
        isEmpty() -> "nothing"
        FileAccessScope.AnyPath in this -> "any file in the workspace"
        else -> sorted().joinToString(", ")
    }
}
