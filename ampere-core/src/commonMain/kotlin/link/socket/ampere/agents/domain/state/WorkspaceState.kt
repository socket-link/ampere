package link.socket.ampere.agents.domain.state

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace

typealias WorkItemId = TaskId

/**
 * A single work item in the workspace checklist.
 *
 * Reuses [TaskStatus] for lifecycle states and [ExecutionWorkspace] for sandbox
 * directory assignment (e.g., a clone of the source repo where an agent operates).
 */
@Serializable
data class WorkItem(
    val id: WorkItemId,
    val title: String,
    val status: TaskStatus,
    val assignedTo: AssignedTo? = null,
    val workspace: ExecutionWorkspace? = null,
    val parentId: WorkItemId? = null,
    val blockedBy: Set<WorkItemId> = emptySet(),
    val progress: Float = 0f,
    val createdAt: Instant,
    val updatedAt: Instant,
    val events: List<EventId> = emptyList(),
)

/**
 * Immutable snapshot of all work items in the workspace.
 *
 * This is the state projected from task lifecycle events via [WorkspaceStateStore].
 * UI layers collect `StateFlow<WorkspaceState>` for reactive updates.
 */
@Serializable
data class WorkspaceState(
    val items: Map<WorkItemId, WorkItem> = emptyMap(),
) {

    /** Top-level items (no parent). */
    val rootItems: List<WorkItem>
        get() = items.values.filter { it.parentId == null }

    /** All children of a given parent item. */
    fun childrenOf(parentId: WorkItemId): List<WorkItem> =
        items.values.filter { it.parentId == parentId }

    /** Compute progress for a parent based on its children's completion. */
    fun computedProgress(itemId: WorkItemId): Float {
        val children = childrenOf(itemId)
        if (children.isEmpty()) return items[itemId]?.progress ?: 0f
        val completed = children.count { it.status.isClosed }
        return completed.toFloat() / children.size
    }

    /** Add a new work item. */
    fun addItem(item: WorkItem): WorkspaceState =
        copy(items = items + (item.id to item))

    /** Update an existing work item by ID. Returns unchanged state if ID not found. */
    fun updateItem(itemId: WorkItemId, transform: WorkItem.() -> WorkItem): WorkspaceState {
        val existing = items[itemId] ?: return this
        return copy(items = items + (itemId to existing.transform()))
    }

    companion object {
        fun empty(): WorkspaceState = WorkspaceState()
    }
}
