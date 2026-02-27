package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
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
 * Items are sorted by status priority: in-progress and blocked items appear
 * first, then pending, then completed (collapsed to a count).
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

        val allItems = state.items.values.toList()
        if (allItems.isEmpty()) {
            lines.add("")
            lines.add(terminal.render(dim("  No tasks yet")))
            lines.add("")
            lines.add(terminal.render(dim("  Tasks will appear here")))
            lines.add(terminal.render(dim("  as agents create them.")))
            return lines.fitToHeight(height, width).map { it.fitToWidth(width) }
        }

        // Partition items by status
        val inProgress = allItems.filter { it.status is TaskStatus.InProgress }
        val blocked = allItems.filter { it.status is TaskStatus.Blocked }
        val pending = allItems.filter { it.status is TaskStatus.Pending }
        val completed = allItems.filter { it.status.isClosed }

        // Reserve 3 lines for summary footer
        val contentHeight = height - lines.size - 3

        // Render active items first (in-progress, blocked, pending)
        val activeItems = inProgress + blocked + pending

        if (activeItems.isNotEmpty()) {
            val visibleActive = activeItems.take(contentHeight / 2) // Each item is ~2 lines
            visibleActive.forEach { item ->
                if (lines.size >= height - 4) return@forEach
                lines.addAll(renderItem(item, width))
            }

            val hidden = activeItems.size - visibleActive.size
            if (hidden > 0) {
                lines.add(terminal.render(dim("  +$hidden more...")))
            }
        }

        // Show completed count (collapsed, not individual items)
        if (completed.isNotEmpty()) {
            if (activeItems.isNotEmpty() && lines.size < height - 4) {
                lines.add("")
            }
            if (lines.size < height - 4) {
                lines.add(terminal.render(TextColors.green("  ✓ ${completed.size} completed")))
            }
        }

        // Pad to leave room for summary
        while (lines.size < height - 3) {
            lines.add("")
        }

        // Summary footer
        val total = allItems.size
        lines.add(terminal.render(dim("─".repeat(width))))
        val summaryParts = mutableListOf("${completed.size}/$total done")
        if (blocked.isNotEmpty()) {
            summaryParts.add(terminal.render(TextColors.red("${blocked.size} blocked")))
        }
        if (inProgress.isNotEmpty()) {
            summaryParts.add(terminal.render(TextColors.cyan("${inProgress.size} active")))
        }
        lines.add(" " + summaryParts.joinToString("  "))

        return lines.fitToHeight(height, width).map { it.fitToWidth(width) }
    }

    private fun renderItem(item: WorkItem, width: Int): List<String> {
        val lines = mutableListOf<String>()

        // Status checkbox
        val checkbox = when (item.status) {
            is TaskStatus.Completed -> terminal.render(TextColors.green("✓"))
            is TaskStatus.InProgress -> terminal.render(TextColors.cyan("◉"))
            is TaskStatus.Blocked -> terminal.render(TextColors.red("!"))
            is TaskStatus.Pending -> terminal.render(dim("○"))
            is TaskStatus.Deferred -> terminal.render(dim("⊘"))
        }

        // Title
        val maxTitleWidth = width - 3
        val truncatedTitle = item.title.take(maxTitleWidth)
        val title = when (item.status) {
            is TaskStatus.Completed -> terminal.render(dim(truncatedTitle))
            is TaskStatus.Blocked -> terminal.render(TextColors.red(truncatedTitle))
            else -> truncatedTitle
        }
        lines.add("$checkbox $title")

        // Metadata line (assignee + progress or blocked reason)
        val meta = buildMetaLine(item, width - 2)
        if (meta.isNotEmpty()) {
            lines.add("  ${terminal.render(dim(meta))}")
        }

        return lines
    }

    private fun buildMetaLine(item: WorkItem, maxWidth: Int): String {
        val parts = mutableListOf<String>()

        // Assignee
        when (val assigned = item.assignedTo) {
            is AssignedTo.Agent -> parts.add(IdFormatter.formatAgentId(assigned.agentId))
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
            is TaskStatus.Blocked -> parts.add(status.reason.take(20))
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
