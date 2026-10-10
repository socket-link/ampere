package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MilestoneCategory
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.state.WorkItem
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.environment.WorkspaceStateStore
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.api.openTaskLifecycle
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.db.Database

/**
 * The lifecycle a unit of work publishes around itself (AMPR-404).
 *
 * `openTaskLifecycle` exists because every production publisher of `TaskCreated` stopped
 * there: the `WorkspaceStateStore` item it added stayed `Pending` for the life of the process
 * and `MilestoneTracker`, which listens for `TaskCompleted`/`TaskFailed`, never had anything to
 * count. These tests assert the three things that were missing — the events on the store under
 * one run id, the item leaving `Pending`, and the milestones firing.
 *
 * `runBlocking`, not `runTest`: the door's write hops to the IO dispatcher, and `runTest`'s
 * virtual clock skips straight past the [DISPATCH_WINDOW_MS] wait while that hop is in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLifecycleTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var eventRepository: EventRepository
    private lateinit var agentEventApiFactory: AgentEventApiFactory
    private lateinit var eventApi: AgentEventApi
    private lateinit var workspaceStateStore: WorkspaceStateStore

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        eventRepository = EventRepository(link.socket.ampere.data.DEFAULT_JSON, scope, database)
        eventSerialBus = EventSerialBusFactory(scope).create()
        agentEventApiFactory = AgentEventApiFactory(eventRepository, eventSerialBus)
        eventApi = agentEventApiFactory.create(AGENT_ID)

        // No repository: nothing is in the store before start, so only the live fold runs and
        // the projection cannot double-apply a replayed event.
        workspaceStateStore = WorkspaceStateStore(eventSerialBus = eventSerialBus, scope = scope)
        workspaceStateStore.start()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a completed lifecycle lands on the store under one run id`() = runBlocking<Unit> {
        val lifecycle = eventApi.openTaskLifecycle(
            taskId = "task-1",
            description = "Do the thing",
            runId = RUN_ID,
            taskType = TASK_TYPE,
        )
        lifecycle.completed("Did the thing")
        delay(DISPATCH_WINDOW_MS)

        val stored = eventRepository.getEventsSinceSequence(0).getOrThrow()

        assertEquals(
            listOf(
                Event.TaskCreated.EVENT_TYPE,
                TaskEvent.TaskStarted.EVENT_TYPE,
                TaskEvent.TaskCompleted.EVENT_TYPE,
            ),
            stored.map { it.event.eventType }.filter { it in TASK_EVENT_TYPES },
        )
        // F4: the whole lifecycle is one run_id query, which is what makes it findable at all —
        // the publishers hand back no event ids to chain `causedBy` through.
        assertEquals(
            listOf(RUN_ID, RUN_ID, RUN_ID),
            stored.filter { it.event.eventType in TASK_EVENT_TYPES }.map { it.runId },
        )
    }

    @Test
    fun `a completed lifecycle moves its work item out of Pending`() = runBlocking<Unit> {
        val lifecycle = eventApi.openTaskLifecycle(
            taskId = "task-1",
            description = "Do the thing",
            runId = RUN_ID,
        )
        delay(DISPATCH_WINDOW_MS)

        // TaskCreated added it; TaskStarted is what takes it off Pending.
        val started = workspaceStateStore.state.value.items.getValue("task-1")
        assertEquals(TaskStatus.InProgress, started.status)
        assertEquals(AssignedTo.Agent(AGENT_ID), started.assignedTo)

        lifecycle.progressed("Halfway", progress = 0.5f)
        delay(DISPATCH_WINDOW_MS)
        assertEquals(0.5f, workspaceStateStore.state.value.items.getValue("task-1").progress)

        lifecycle.completed("Did the thing")
        delay(DISPATCH_WINDOW_MS)

        val completed = workspaceStateStore.state.value.items.getValue("task-1")
        assertIs<TaskStatus.Completed>(completed.status)
        assertEquals(1f, completed.progress)
    }

    @Test
    fun `a failed lifecycle blocks its work item with the reason`() = runBlocking<Unit> {
        val lifecycle = eventApi.openTaskLifecycle(
            taskId = "task-1",
            description = "Do the thing",
            runId = RUN_ID,
        )
        lifecycle.failed("Upstream refused")
        delay(DISPATCH_WINDOW_MS)

        val blocked = workspaceStateStore.state.value.items.getValue("task-1")
        assertEquals(TaskStatus.Blocked(reason = "Upstream refused"), blocked.status)
    }

    @Test
    fun `the first terminal event wins`() = runBlocking<Unit> {
        val lifecycle = eventApi.openTaskLifecycle(
            taskId = "task-1",
            description = "Do the thing",
            runId = RUN_ID,
        )

        assertTrue(lifecycle.completed("Did the thing"))
        assertTrue(lifecycle.isTerminal)
        // The `finally` shape every caller uses: a no-op once the work reported its outcome.
        assertFalse(lifecycle.failed("Abandoned"))
        delay(DISPATCH_WINDOW_MS)

        assertIs<TaskStatus.Completed>(
            workspaceStateStore.state.value.items.getValue("task-1").status,
        )
        assertEquals(
            0,
            eventRepository.getEventsByType(TaskEvent.TaskFailed.EVENT_TYPE).getOrThrow().size,
        )
    }

    @Test
    fun `a cancelled caller still records how the work ended`() = runBlocking<Unit> {
        val lifecycle = eventApi.openTaskLifecycle(
            taskId = "task-1",
            description = "Do the thing",
            runId = RUN_ID,
        )

        // Launched on runBlocking's own scope, so the cancellation and the `finally` run on
        // real time rather than on the TestScope's virtual clock. `entered` is what makes the
        // test deterministic: cancelling a coroutine that has not started yet skips its body,
        // `finally` included, so wait until it is actually inside the delay.
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            try {
                entered.complete(Unit)
                delay(LONG_ENOUGH_TO_BE_CANCELLED_MS)
            } finally {
                // Terminal publishes run under NonCancellable, so the work's outcome is
                // recorded even though the coroutine doing it is already cancelled.
                lifecycle.failed("Cancelled before completing")
            }
        }
        entered.await()
        job.cancelAndJoin()
        delay(DISPATCH_WINDOW_MS)

        assertEquals(
            1,
            eventRepository.getEventsByType(TaskEvent.TaskFailed.EVENT_TYPE).getOrThrow().size,
        )
        assertEquals(
            TaskStatus.Blocked(reason = "Cancelled before completing"),
            workspaceStateStore.state.value.items.getValue("task-1").status,
        )
    }

    @Test
    fun `plan steps published as subtasks become child items`() = runBlocking<Unit> {
        val lifecycle = eventApi.openTaskLifecycle(
            taskId = "task-1",
            description = "Do the thing",
            runId = RUN_ID,
        )
        lifecycle.subtaskCreated(subtaskId = "step-1", description = "First step")
        lifecycle.subtaskCreated(subtaskId = "step-2", description = "Second step")
        delay(DISPATCH_WINDOW_MS)

        val children = workspaceStateStore.state.value.childrenOf("task-1")
        assertEquals(listOf("step-1", "step-2"), children.map(WorkItem::id).sorted())
        assertEquals(listOf("task-1"), workspaceStateStore.state.value.rootItems.map(WorkItem::id))
    }

    @Test
    fun `the first completion of a task type reaches a FIRST_SUCCESS milestone`() = runBlocking<Unit> {
        val milestones = collectMilestones()

        eventApi.openTaskLifecycle(taskId = "task-1", description = "One", runId = RUN_ID, taskType = TASK_TYPE)
            .completed("Done")
        delay(DISPATCH_WINDOW_MS)

        assertEquals(listOf(MilestoneCategory.FIRST_SUCCESS), milestones.map { it.category })

        // The second task of the same type is no longer a first.
        eventApi.openTaskLifecycle(taskId = "task-2", description = "Two", runId = RUN_ID, taskType = TASK_TYPE)
            .completed("Done")
        delay(DISPATCH_WINDOW_MS)

        assertEquals(listOf(MilestoneCategory.FIRST_SUCCESS), milestones.map { it.category })
    }

    @Test
    fun `completing a task that previously failed reaches a RECOVERY milestone`() = runBlocking<Unit> {
        val milestones = collectMilestones()

        // A retry is a second lifecycle over the same task id — the first one is terminal.
        eventApi.openTaskLifecycle(taskId = "task-1", description = "Attempt", runId = RUN_ID, taskType = TASK_TYPE)
            .failed("Upstream refused")
        delay(DISPATCH_WINDOW_MS)
        assertEquals(emptyList(), milestones.map { it.category })

        eventApi.openTaskLifecycle(taskId = "task-1", description = "Retry", runId = RETRY_RUN_ID, taskType = TASK_TYPE)
            .completed("Done on the retry")
        delay(DISPATCH_WINDOW_MS)

        assertEquals(
            listOf(MilestoneCategory.FIRST_SUCCESS, MilestoneCategory.RECOVERY),
            milestones.map { it.category },
        )
        assertEquals(listOf("task-1", "task-1"), milestones.map { it.taskId })
    }

    /** Capture every milestone `MilestoneTracker` publishes, in dispatch order. */
    private fun collectMilestones(): MutableList<MemoryEvent.MilestoneReached> {
        val milestones = mutableListOf<MemoryEvent.MilestoneReached>()

        eventSerialBus.subscribe<MemoryEvent.MilestoneReached, Subscription>(
            agentId = "observer",
            eventType = MemoryEvent.MilestoneReached.EVENT_TYPE,
        ) { event, _ ->
            milestones += event
        }

        return milestones
    }

    private companion object {
        const val AGENT_ID = "worker"
        const val RUN_ID = "run-lifecycle-1"
        const val RETRY_RUN_ID = "run-lifecycle-2"
        const val TASK_TYPE = "demo-task"

        /** Long enough for the door's write plus the bus's asynchronous dispatch. */
        const val DISPATCH_WINDOW_MS = 200L

        /** Longer than the test will wait, so the cancellation lands inside the `delay`. */
        const val LONG_ENOUGH_TO_BE_CANCELLED_MS = 60_000L

        val TASK_EVENT_TYPES = setOf(
            Event.TaskCreated.EVENT_TYPE,
            TaskEvent.TaskStarted.EVENT_TYPE,
            TaskEvent.TaskProgressed.EVENT_TYPE,
            TaskEvent.TaskCompleted.EVENT_TYPE,
            TaskEvent.TaskFailed.EVENT_TYPE,
            TaskEvent.TaskBlocked.EVENT_TYPE,
            TaskEvent.SubtaskCreated.EVENT_TYPE,
        )
    }
}
