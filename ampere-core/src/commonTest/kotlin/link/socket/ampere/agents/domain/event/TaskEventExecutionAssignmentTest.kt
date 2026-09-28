package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.task.EffortLevel
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.domain.task.WorkPhase
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.environment.workspace.WorkspaceLease
import link.socket.ampere.data.DEFAULT_JSON

/**
 * The task events are what carry a phase, a model and an effort into the
 * workspace projection (AMPR-369), and they are persisted, so each must
 * round-trip through the event store's own [DEFAULT_JSON] and still decode
 * rows written before the fields existed.
 */
class TaskEventExecutionAssignmentTest {

    private val json = DEFAULT_JSON

    private val timestamp = Instant.parse("2026-09-27T12:00:00Z")
    private val source = EventSource.Agent("agent-A")
    private val execution = ExecutionAssignment(model = "claude-sonnet-5", effort = EffortLevel.HIGH)
    private val workspace = ExecutionWorkspace(
        baseDirectory = "/work/ampere-a",
        repository = "socket-link/ampere",
        branch = "miley/ampr-369",
        lease = WorkspaceLease(holder = "run-1", acquiredAt = timestamp),
    )

    private val taskCreated = Event.TaskCreated(
        eventId = "evt-1",
        urgency = Urgency.MEDIUM,
        timestamp = timestamp,
        eventSource = source,
        taskId = "task-1",
        description = "Map the dispatch seam",
        assignedTo = "agent-B",
        phase = WorkPhase.RECON,
        execution = execution,
    )

    private val taskStarted = TaskEvent.TaskStarted(
        eventId = "evt-2",
        taskId = "task-1",
        eventSource = source,
        timestamp = timestamp,
        assignedTo = "agent-B",
        workspace = workspace,
        execution = execution,
    )

    private val subtaskCreated = TaskEvent.SubtaskCreated(
        eventId = "evt-3",
        taskId = "task-1",
        eventSource = source,
        timestamp = timestamp,
        subtaskId = "task-1-a",
        description = "Write the adapter",
        assignedTo = "agent-C",
        workspace = workspace,
        phase = WorkPhase.IMPLEMENTATION,
        execution = execution,
    )

    private fun roundTrip(event: Event): Event =
        json.decodeFromString(Event.serializer(), json.encodeToString(Event.serializer(), event))

    private fun decodeWithout(event: Event, keys: Set<String>): Event {
        val encoded = json.encodeToJsonElement(Event.serializer(), event).jsonObject
        assertTrue(keys.all { it in encoded }, "expected $keys in ${encoded.keys}")
        return json.decodeFromJsonElement(Event.serializer(), JsonObject(encoded - keys))
    }

    @Test
    fun `TaskCreated round-trips with phase and execution`() {
        assertEquals(taskCreated, roundTrip(taskCreated))
    }

    @Test
    fun `TaskStarted round-trips with execution and a leased workspace`() {
        assertEquals(taskStarted, roundTrip(taskStarted))
    }

    @Test
    fun `SubtaskCreated round-trips with phase and execution`() {
        assertEquals(subtaskCreated, roundTrip(subtaskCreated))
    }

    @Test
    fun `TaskCreated decodes a row written before the new fields existed`() {
        assertEquals(
            taskCreated.copy(phase = null, execution = null),
            decodeWithout(taskCreated, setOf("phase", "execution")),
        )
    }

    @Test
    fun `TaskStarted decodes a row written before the new fields existed`() {
        assertEquals(
            taskStarted.copy(execution = null),
            decodeWithout(taskStarted, setOf("execution")),
        )
    }

    @Test
    fun `SubtaskCreated decodes a row written before the new fields existed`() {
        assertEquals(
            subtaskCreated.copy(phase = null, execution = null),
            decodeWithout(subtaskCreated, setOf("phase", "execution")),
        )
    }
}
