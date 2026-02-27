package link.socket.ampere.agents.environment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.state.WorkspaceState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.bus.EventSerialBus

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceStateStoreTest {

    private val clock = Clock.System
    private val now: Instant get() = clock.now()

    // ==================== Pure fold() tests ====================

    @Test
    fun `fold TaskCreated adds item to empty state`() {
        val event = Event.TaskCreated(
            eventId = "evt-1",
            urgency = Urgency.MEDIUM,
            timestamp = now,
            eventSource = EventSource.Agent("agent-A"),
            taskId = "task-1",
            description = "Implement authentication",
            assignedTo = "agent-B",
        )

        val result = WorkspaceStateStore.fold(WorkspaceState.empty(), event)

        assertEquals(1, result.items.size)
        val item = result.items["task-1"]
        assertNotNull(item)
        assertEquals("Implement authentication", item.title)
        assertEquals(TaskStatus.Pending, item.status)
        assertEquals(AssignedTo.Agent("agent-B"), item.assignedTo)
        assertNull(item.parentId)
        assertEquals(0f, item.progress)
        assertEquals(listOf("evt-1"), item.events)
    }

    @Test
    fun `fold TaskStarted transitions to InProgress with workspace`() {
        val workspace = ExecutionWorkspace(baseDirectory = "/tmp/workspaces/agent-B")
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Implement auth",
                assignedTo = "agent-B",
            ),
        )

        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskStarted(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-B"),
                timestamp = now,
                assignedTo = "agent-B",
                workspace = workspace,
            ),
        )

        val item = state.items["task-1"]
        assertNotNull(item)
        assertEquals(TaskStatus.InProgress, item.status)
        assertEquals(workspace, item.workspace)
        assertEquals(AssignedTo.Agent("agent-B"), item.assignedTo)
        assertEquals(2, item.events.size)
    }

    @Test
    fun `fold TaskProgressed increments progress`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Build feature",
                assignedTo = null,
            ),
        )

        // Progress without explicit value: increments by 0.1
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskProgressed(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                description = "Wrote initial code",
            ),
        )

        assertEquals(0.1f, state.items["task-1"]!!.progress)

        // Progress with explicit value
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskProgressed(
                eventId = "evt-3",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                description = "Tests passing",
                progress = 0.75f,
            ),
        )

        assertEquals(0.75f, state.items["task-1"]!!.progress)
    }

    @Test
    fun `fold TaskProgressed caps at 0_9 without explicit progress`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Build feature",
                assignedTo = null,
            ),
        )

        // Push progress past 0.9 with 10 incremental updates
        repeat(10) { i ->
            state = WorkspaceStateStore.fold(
                state,
                TaskEvent.TaskProgressed(
                    eventId = "evt-prog-$i",
                    taskId = "task-1",
                    eventSource = EventSource.Agent("agent-A"),
                    timestamp = now,
                    description = "Step $i",
                ),
            )
        }

        assertTrue(state.items["task-1"]!!.progress <= 0.9f)
    }

    @Test
    fun `fold TaskCompleted sets status and full progress`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Build feature",
                assignedTo = "agent-A",
            ),
        )

        val completedAt = now
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskCompleted(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = completedAt,
                summary = "Feature built and tested",
            ),
        )

        val item = state.items["task-1"]!!
        assertTrue(item.status is TaskStatus.Completed)
        assertEquals(1f, item.progress)
        assertEquals(completedAt, (item.status as TaskStatus.Completed).completedAt)
    }

    @Test
    fun `fold TaskFailed sets Blocked status with reason`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Deploy service",
                assignedTo = null,
            ),
        )

        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskFailed(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                reason = "Build failed: missing dependency",
            ),
        )

        val item = state.items["task-1"]!!
        assertTrue(item.status is TaskStatus.Blocked)
        assertEquals("Build failed: missing dependency", (item.status as TaskStatus.Blocked).reason)
    }

    @Test
    fun `fold TaskBlocked adds to blockedBy set`() {
        var state = WorkspaceState.empty()

        // Create two tasks
        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Task A",
                assignedTo = null,
            ),
        )
        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-2",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-2",
                description = "Task B",
                assignedTo = null,
            ),
        )

        // Block task-2 on task-1
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskBlocked(
                eventId = "evt-3",
                taskId = "task-2",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                blockedByTaskId = "task-1",
                reason = "Needs task-1 completed first",
            ),
        )

        val item = state.items["task-2"]!!
        assertTrue(item.status is TaskStatus.Blocked)
        assertTrue("task-1" in item.blockedBy)
    }

    @Test
    fun `fold SubtaskCreated adds child item with parentId`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Build auth system",
                assignedTo = null,
            ),
        )

        val childWorkspace = ExecutionWorkspace(baseDirectory = "/tmp/workspaces/agent-C")
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.SubtaskCreated(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                subtaskId = "task-1-a",
                description = "Implement login endpoint",
                assignedTo = "agent-C",
                workspace = childWorkspace,
            ),
        )

        assertEquals(2, state.items.size)

        val child = state.items["task-1-a"]!!
        assertEquals("Implement login endpoint", child.title)
        assertEquals("task-1", child.parentId)
        assertEquals(AssignedTo.Agent("agent-C"), child.assignedTo)
        assertEquals(childWorkspace, child.workspace)
        assertEquals(TaskStatus.Pending, child.status)
    }

    @Test
    fun `rootItems returns only items without parentId`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Parent task",
                assignedTo = null,
            ),
        )

        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.SubtaskCreated(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                subtaskId = "task-1-a",
                description = "Child task",
            ),
        )

        val roots = state.rootItems
        assertEquals(1, roots.size)
        assertEquals("task-1", roots[0].id)
    }

    @Test
    fun `childrenOf returns subtasks`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Parent",
                assignedTo = null,
            ),
        )

        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.SubtaskCreated(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                subtaskId = "task-1-a",
                description = "Child A",
            ),
        )

        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.SubtaskCreated(
                eventId = "evt-3",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                subtaskId = "task-1-b",
                description = "Child B",
            ),
        )

        val children = state.childrenOf("task-1")
        assertEquals(2, children.size)
        assertTrue(children.any { it.id == "task-1-a" })
        assertTrue(children.any { it.id == "task-1-b" })
    }

    @Test
    fun `computedProgress based on child completion`() {
        var state = WorkspaceState.empty()

        state = WorkspaceStateStore.fold(
            state,
            Event.TaskCreated(
                eventId = "evt-1",
                urgency = Urgency.MEDIUM,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                taskId = "task-1",
                description = "Parent",
                assignedTo = null,
            ),
        )

        // Add 2 subtasks
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.SubtaskCreated(
                eventId = "evt-2",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                subtaskId = "task-1-a",
                description = "Child A",
            ),
        )
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.SubtaskCreated(
                eventId = "evt-3",
                taskId = "task-1",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                subtaskId = "task-1-b",
                description = "Child B",
            ),
        )

        // 0 of 2 completed
        assertEquals(0f, state.computedProgress("task-1"))

        // Complete one child
        state = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskCompleted(
                eventId = "evt-4",
                taskId = "task-1-a",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                summary = "Done",
            ),
        )

        // 1 of 2 completed
        assertEquals(0.5f, state.computedProgress("task-1"))
    }

    @Test
    fun `fold ignores unrelated events`() {
        val state = WorkspaceState.empty()

        val result = WorkspaceStateStore.fold(
            state,
            Event.QuestionRaised(
                eventId = "evt-q",
                urgency = Urgency.LOW,
                timestamp = now,
                eventSource = EventSource.Agent("agent-A"),
                questionText = "What color?",
                context = "Design question",
            ),
        )

        assertEquals(state, result)
    }

    @Test
    fun `fold ignores events for unknown task IDs`() {
        val state = WorkspaceState.empty()

        val result = WorkspaceStateStore.fold(
            state,
            TaskEvent.TaskStarted(
                eventId = "evt-1",
                taskId = "nonexistent-task",
                eventSource = EventSource.Agent("agent-A"),
                timestamp = now,
                assignedTo = "agent-A",
            ),
        )

        assertEquals(state, result)
    }

    // ==================== Integration test with EventSerialBus ====================

    @Test
    fun `store reacts to events published on the bus`() {
        runBlocking {
            val scope = TestScope(UnconfinedTestDispatcher())
            val bus = EventSerialBus(scope)
            val store = WorkspaceStateStore(
                eventSerialBus = bus,
                scope = scope,
            )

            store.start()

            // Publish a TaskCreated event to the bus
            bus.publish(
                Event.TaskCreated(
                    eventId = "evt-1",
                    urgency = Urgency.HIGH,
                    timestamp = now,
                    eventSource = EventSource.Agent("agent-A"),
                    taskId = "task-1",
                    description = "Build the widget",
                    assignedTo = "agent-B",
                ),
            )

            delay(100)

            val currentState = store.state.value
            assertEquals(1, currentState.items.size)
            assertEquals("Build the widget", currentState.items["task-1"]!!.title)

            // Publish TaskStarted
            bus.publish(
                TaskEvent.TaskStarted(
                    eventId = "evt-2",
                    taskId = "task-1",
                    eventSource = EventSource.Agent("agent-B"),
                    timestamp = now,
                    assignedTo = "agent-B",
                    workspace = ExecutionWorkspace(baseDirectory = "/tmp/agent-B-workspace"),
                ),
            )

            delay(100)

            val updatedState = store.state.value
            assertEquals(TaskStatus.InProgress, updatedState.items["task-1"]!!.status)
            assertEquals("/tmp/agent-B-workspace", updatedState.items["task-1"]!!.workspace!!.baseDirectory)
        }
    }

    @Test
    fun `full task lifecycle through bus`() {
        runBlocking {
            val scope = TestScope(UnconfinedTestDispatcher())
            val bus = EventSerialBus(scope)
            val store = WorkspaceStateStore(
                eventSerialBus = bus,
                scope = scope,
            )

            store.start()

            // Create
            bus.publish(
                Event.TaskCreated(
                    eventId = "evt-1",
                    urgency = Urgency.MEDIUM,
                    timestamp = now,
                    eventSource = EventSource.Agent("agent-A"),
                    taskId = "task-1",
                    description = "Deploy service",
                    assignedTo = "agent-A",
                ),
            )

            // Start
            bus.publish(
                TaskEvent.TaskStarted(
                    eventId = "evt-2",
                    taskId = "task-1",
                    eventSource = EventSource.Agent("agent-A"),
                    timestamp = now,
                    assignedTo = "agent-A",
                ),
            )

            // Progress
            bus.publish(
                TaskEvent.TaskProgressed(
                    eventId = "evt-3",
                    taskId = "task-1",
                    eventSource = EventSource.Agent("agent-A"),
                    timestamp = now,
                    description = "Docker image built",
                    progress = 0.5f,
                ),
            )

            // Complete
            bus.publish(
                TaskEvent.TaskCompleted(
                    eventId = "evt-4",
                    taskId = "task-1",
                    eventSource = EventSource.Agent("agent-A"),
                    timestamp = now,
                    summary = "Deployed to staging",
                ),
            )

            delay(100)

            val finalState = store.state.value
            val item = finalState.items["task-1"]!!

            assertTrue(item.status is TaskStatus.Completed)
            assertEquals(1f, item.progress)
            assertEquals(4, item.events.size)
        }
    }
}
