package link.socket.ampere.goal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject

/**
 * [SqlDelightGoalStore] (AMPR-419, F8): the Goal branch's rows on `JdbcSqliteDriver(IN_MEMORY)`,
 * brought to the current schema the way every JVM open is.
 */
class GoalStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SqlDelightGoalStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseSchemaManager.ensure(driver).getOrThrow()
        store = SqlDelightGoalStore(Database(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun record(
        id: String,
        parent: String? = null,
        ordinal: Int = 0,
        description: String = "Goal $id",
        status: GoalStatus = GoalStatus.OPEN,
        subject: String = "ampere.arc-runtime",
        runId: String? = "run-1",
        createdAt: Long = 1_000,
    ) = GoalRecord(
        goal = Goal(
            id = Ref(id),
            description = description,
            parent = parent?.let { Ref<Goal>(it) },
            provenance = Provenance(createdBy = "evt-$id", createdAt = Instant.fromEpochMilliseconds(createdAt)),
        ),
        status = status,
        subject = Ref(subject),
        runId = runId,
        ordinal = ordinal,
    )

    @Test
    fun `a goal round-trips through the store with its provenance`() = runBlocking<Unit> {
        val formed = record("run-1/goal-0-1", parent = "run-1/goal-0", ordinal = 1)

        assertEquals(formed, store.insert(formed).getOrThrow())
        assertEquals(formed, store.get(formed.id).getOrThrow())
        assertEquals(formed.goal.provenance, formed.lastUpdate, "a fresh goal's latest write is its formation")
    }

    @Test
    fun `inserting an id already on the branch returns the first goal unchanged`() = runBlocking<Unit> {
        val first = record("run-1/goal-0", description = "Ship login")
        store.insert(first).getOrThrow()

        val again = store.insert(record("run-1/goal-0", description = "Something else")).getOrThrow()

        assertEquals(first, again)
        assertEquals("Ship login", store.get(first.id).getOrThrow()?.goal?.description)
    }

    @Test
    fun `a run's goals come back in sibling order and children by parent`() = runBlocking<Unit> {
        store.insert(record("run-1/goal-0-1", parent = "run-1/goal-0", ordinal = 1)).getOrThrow()
        store.insert(record("run-1/goal-0")).getOrThrow()
        store.insert(record("run-1/goal-0-0", parent = "run-1/goal-0", ordinal = 0)).getOrThrow()
        store.insert(record("run-2/goal-0", runId = "run-2")).getOrThrow()

        assertEquals(
            setOf("run-1/goal-0", "run-1/goal-0-0", "run-1/goal-0-1"),
            store.forRun("run-1").getOrThrow().map { it.id.value }.toSet(),
        )
        assertEquals(
            listOf("run-1/goal-0-0", "run-1/goal-0-1"),
            store.children(Ref("run-1/goal-0")).getOrThrow().map { it.id.value },
        )
    }

    @Test
    fun `a subject's open goals leave out completed ones and other subjects`() = runBlocking<Unit> {
        store.insert(record("run-1/goal-0", createdAt = 2_000)).getOrThrow()
        store.insert(record("run-0/goal-0", runId = "run-0", createdAt = 1_000)).getOrThrow()
        store.insert(record("run-1/goal-0-0", parent = "run-1/goal-0", status = GoalStatus.COMPLETED)).getOrThrow()
        store.insert(record("other/goal-0", subject = "someone-else")).getOrThrow()

        assertEquals(
            listOf("run-0/goal-0", "run-1/goal-0"),
            store.forSubject(Ref<Subject>("ampere.arc-runtime"), GoalStatus.OPEN).getOrThrow().map { it.id.value },
        )
    }

    @Test
    fun `moving a status records the event that moved it and keeps the formation`() = runBlocking<Unit> {
        val formed = store.insert(record("run-1/goal-0")).getOrThrow()
        val update = Provenance(createdBy = "evt-updated", createdAt = Instant.fromEpochMilliseconds(5_000))

        val moved = store.updateStatus(formed.id, GoalStatus.COMPLETED, update).getOrThrow()

        assertEquals(GoalStatus.COMPLETED, moved?.status)
        assertEquals(update, moved?.lastUpdate)
        assertEquals(formed.goal.provenance, moved?.goal?.provenance)
    }

    @Test
    fun `an abandoned goal reads back abandoned and leaves the subject's open goals`() = runBlocking<Unit> {
        val formed = store.insert(record("run-1/goal-0")).getOrThrow()
        val update = Provenance(createdBy = "evt-abandoned", createdAt = Instant.fromEpochMilliseconds(5_000))

        store.updateStatus(formed.id, GoalStatus.ABANDONED, update).getOrThrow()

        assertEquals(GoalStatus.ABANDONED, store.get(formed.id).getOrThrow()?.status)
        val subject = Ref<Subject>("ampere.arc-runtime")
        assertEquals(emptyList(), store.forSubject(subject, GoalStatus.OPEN).getOrThrow())
        assertEquals(
            listOf("run-1/goal-0"),
            store.forSubject(subject, GoalStatus.ABANDONED).getOrThrow().map { it.id.value },
        )
    }

    @Test
    fun `moving a goal nothing formed returns null`() = runBlocking<Unit> {
        val update = Provenance(createdBy = "evt-updated", createdAt = Instant.fromEpochMilliseconds(5_000))

        assertNull(store.updateStatus(Ref("run-1/missing"), GoalStatus.COMPLETED, update).getOrThrow())
    }

    @Test
    fun `a status this build does not know is reported by name`() = runBlocking<Unit> {
        store.insert(record("run-1/goal-0")).getOrThrow()
        driver.execute(null, "UPDATE GoalStore SET status = 'DEFERRED' WHERE id = 'run-1/goal-0'", 0)

        val failure = store.get(Ref("run-1/goal-0")).exceptionOrNull()

        val unknown = assertIs<UnknownGoalStatusException>(failure)
        assertEquals("DEFERRED", unknown.status)
        assertIs<UnknownGoalStatusException>(store.forRun("run-1").exceptionOrNull())
    }
}
