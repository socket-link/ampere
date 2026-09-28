package link.socket.ampere.agents.domain.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.EffortLevel
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.domain.task.WorkPhase
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.environment.workspace.WorkspaceLease
import link.socket.ampere.data.DEFAULT_JSON

class WorkItemSerializationTest {

    private val json = DEFAULT_JSON

    private val createdAt = Instant.parse("2026-09-27T12:00:00Z")

    private val fullItem = WorkItem(
        id = "task-1",
        title = "Map the dispatch seam",
        status = TaskStatus.InProgress,
        assignedTo = AssignedTo.Agent("agent-B"),
        workspace = ExecutionWorkspace(
            baseDirectory = "/work/ampere-a",
            repository = "socket-link/ampere",
            branch = "miley/ampr-369",
            lease = WorkspaceLease(holder = "run-1", acquiredAt = createdAt),
        ),
        createdAt = createdAt,
        updatedAt = createdAt,
        events = listOf("evt-1", "evt-2"),
        phase = WorkPhase.RECON,
        execution = ExecutionAssignment(model = "claude-sonnet-5", effort = EffortLevel.MEDIUM),
    )

    @Test
    fun `WorkItem round-trips with phase model and effort`() {
        val encoded = json.encodeToString(WorkItem.serializer(), fullItem)
        val decoded = json.decodeFromString(WorkItem.serializer(), encoded)

        assertEquals(fullItem, decoded)
    }

    @Test
    fun `WorkItem round-trips without phase or execution`() {
        val original = WorkItem(
            id = "task-1",
            title = "Unclassified work",
            status = TaskStatus.Pending,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        val encoded = json.encodeToString(WorkItem.serializer(), original)
        val decoded = json.decodeFromString(WorkItem.serializer(), encoded)

        assertEquals(original, decoded)
        assertNull(decoded.phase)
        assertNull(decoded.execution)
    }

    @Test
    fun `WorkItem decodes a payload written before the new fields existed`() {
        val encoded = json.encodeToJsonElement(WorkItem.serializer(), fullItem).jsonObject
        assertTrue(NEW_KEYS.all { it in encoded }, "expected $NEW_KEYS in ${encoded.keys}")
        val legacy = JsonObject(encoded - NEW_KEYS)

        val decoded = json.decodeFromJsonElement(WorkItem.serializer(), legacy)

        assertEquals(fullItem.copy(phase = null, execution = null), decoded)
    }

    @Test
    fun `WorkspaceState round-trips items that carry the new fields`() {
        val original = WorkspaceState.empty().addItem(fullItem)

        val encoded = json.encodeToString(WorkspaceState.serializer(), original)
        val decoded = json.decodeFromString(WorkspaceState.serializer(), encoded)

        assertEquals(original, decoded)
    }

    private companion object {
        val NEW_KEYS = setOf("phase", "execution")
    }
}
