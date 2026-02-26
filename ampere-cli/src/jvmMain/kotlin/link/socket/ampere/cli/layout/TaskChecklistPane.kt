package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.agents.domain.state.WorkItem
import link.socket.ampere.agents.domain.state.WorkspaceState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo

/**
 * Pane displaying the current workspace task checklist.
 *
 * Reads from [WorkspaceState] (projected by WorkspaceStateStore) and renders
 * a Claude/Codex-style checklist showing each task's status, progress,
 * assignee, and subtask count.
 *
 * Designed for the right column (~25% width) of the 3-column layout,
 * but can work in any pane slot.
 */
class TaskChecklistPane(
    private val terminal: Terminal,
) : PaneRenderer {

    private var state: WorkspaceState = WorkspaceState.empty()

    fun updateState(newState: WorkspaceState) {
        state = newState
    }

    override fun render(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        lines.addAll(renderSectionHeader("Tasks", width, terminal))

        val rootItems = state.rootItems
        if (rootItems.isEmpty()) {
            lines.add("")
            lines.add(terminal.render(dim("  No tasks yet")))
            lines.add("")
            lines.add(terminal.render(dim("  Tasks will appear here")))
            lines.add(terminal.render(dim("  as agents create them.")))
        } else {
            rootItems.forEach { item ->
                if (lines.size >= height - 2) return@forEach
                lines.addAll(renderItem(item, width, indent = 0))

                // Render children (subtasks)
                val children = state.childrenOf(item.id)
                children.forEach { child ->
                    if (lines.size >= height - 2) return@forEach
                    lines.addAll(renderItem(child, width, indent = 1))
                }
            }
        }

        // Summary line at the bottom
        if (rootItems.isNotEmpty()) {
            val allItems = state.items.values
            val completed = allItems.count { it.status.isClosed }
            val total = allItems.size
            val blocked = allItems.count { it.status is TaskStatus.Blocked }

            // Pad to leave room for summary
            while (lines.size < height - 3) {
                lines.add("")
            }

            lines.add(terminal.render(dim("─".repeat(width))))
            val summaryParts = mutableListOf("$completed/$total done")
            if (blocked > 0) {
                summaryParts.add(terminal.render(TextColors.red("$blocked blocked")))
            }
            lines.add(terminal.render(dim(" ")) + summaryParts.joinToString("  "))
        }

        return lines.fitToHeight(height, width).map { it.fitToWidth(width) }
    }

    private fun renderItem(item: WorkItem, width: Int, indent: Int): List<String> {
        val lines = mutableListOf<String>()
        val prefix = "  ".repeat(indent)

        // Status checkbox
        val checkbox = when (item.status) {
            is TaskStatus.Completed -> terminal.render(TextColors.green("✓"))
            is TaskStatus.InProgress -> terminal.render(TextColors.cyan("◉"))
            is TaskStatus.Blocked -> terminal.render(TextColors.red("!"))
            is TaskStatus.Pending -> terminal.render(dim("○"))
            is TaskStatus.Deferred -> terminal.render(dim("⊘"))
        }

        // First line: checkbox + title
        val maxTitleWidth = width - (indent * 2) - 3
        val truncatedTitle = item.title.take(maxTitleWidth)
        val title = when (item.status) {
            is TaskStatus.Completed -> terminal.render(dim(truncatedTitle))
            is TaskStatus.Blocked -> terminal.render(TextColors.red(truncatedTitle))
            else -> truncatedTitle
        }
        lines.add("$prefix$checkbox $title")

        // Second line: metadata (assignee + progress or blocked reason)
        val meta = buildMetaLine(item, width - (indent * 2) - 2)
        if (meta.isNotEmpty()) {
            lines.add("$prefix  ${terminal.render(dim(meta))}")
        }

        return lines
    }

    private fun buildMetaLine(item: WorkItem, maxWidth: Int): String {
        val parts = mutableListOf<String>()

        // Assignee
        when (val assigned = item.assignedTo) {
            is AssignedTo.Agent -> {
                val agentName = IdFormatter.formatAgentId(assigned.agentId)
                parts.add(agentName)
            }
            is AssignedTo.Team -> parts.add("team:${assigned.teamId}")
            is AssignedTo.Human -> parts.add("human")
            null -> {}
        }

        // Progress or blocked reason
        when (val status = item.status) {
            is TaskStatus.InProgress -> {
                if (item.progress > 0f) {
                    parts.add("${(item.progress * 100).toInt()}%")
                }
            }
            is TaskStatus.Blocked -> {
                parts.add(status.reason.take(20))
            }
            else -> {}
        }

        // Subtask count
        val children = state.childrenOf(item.id)
        if (children.isNotEmpty()) {
            val done = children.count { it.status.isClosed }
            parts.add("${done}/${children.size} subtasks")
        }

        return parts.joinToString(" · ").take(maxWidth)
    }
}
