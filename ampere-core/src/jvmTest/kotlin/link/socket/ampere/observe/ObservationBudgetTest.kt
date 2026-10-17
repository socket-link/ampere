package link.socket.ampere.observe

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Decay
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.room.FixedClock

/**
 * [ObservationBudget] as [SqlDelightObservationStore] enforces it (AMPR-465): the age pass, the
 * expiry pass, the row cap, the marker each pass leaves, and the sanctioned forget queries.
 *
 * The bounds are shrunk through the constructor so a test writes tens of rows, not hundreds of
 * thousands; the policy is the same. The clock is pinned and moved by hand.
 */
class ObservationBudgetTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = FixedClock(epoch)
    private val holder = Ref<Subject>("agent-scout")
    private val other = Ref<Subject>("agent-other")

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseSchemaManager.ensure(driver).getOrThrow()
        database = Database(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    /** A store that sweeps on every insert unless told otherwise, with the production age cap. */
    private fun store(
        maxRows: Long = ObservationBudget.MAX_ROWS,
        pruneToRows: Long = ObservationBudget.PRUNE_TO_ROWS,
        sweepIntervalInserts: Long = 1,
    ) = SqlDelightObservationStore(
        database = database,
        clock = clock,
        maxRows = maxRows,
        pruneToRows = pruneToRows,
        sweepIntervalInserts = sweepIntervalInserts,
    )

    private fun observation(
        event: String,
        createdAt: Instant,
        subject: String = "canon/link/$event",
        holder: Ref<Subject> = this.holder,
        sensitivity: Sensitivity = Sensitivity.PRIVATE,
        decay: Decay = Decay.None,
    ) = Belief.Settled(
        holder = holder,
        subject = Ref(subject),
        content = "Observed $subject",
        kind = BeliefKind.OBSERVATION,
        confidence = 1.0,
        sensitivity = sensitivity,
        decay = decay,
        provenance = Provenance(createdBy = event, createdAt = createdAt),
    )

    private fun rowCount(): Long = database.observationStoreQueries.countObservations().executeAsOne()

    private fun events(): List<String> = driver.executeQuery(
        null,
        "SELECT created_by_event FROM ObservationStore ORDER BY created_at ASC, created_by_event ASC",
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(cursor.getString(0)!!)
                },
            )
        },
        0,
    ).value

    // ==================== Age ====================

    @Test
    fun `a row older than MAX_AGE is gone after the next sweep and a younger one is not`() = runBlocking<Unit> {
        val store = store()
        clock.current = epoch + ObservationBudget.MAX_AGE + 10.days
        store.insert(
            listOf(
                observation("evt-stale", createdAt = epoch),
                observation("evt-fresh", createdAt = clock.current - ObservationBudget.MAX_AGE + 1.days),
            ),
        ).getOrThrow()

        // Each insert sweeps after its own transaction commits, so the first one already dropped the stale row.
        store.insert(listOf(observation("evt-trigger", createdAt = clock.current))).getOrThrow()

        assertEquals(listOf("evt-fresh", "evt-trigger"), events())
    }

    @Test
    fun `the age pass measures against the store's clock and not the wall`() = runBlocking<Unit> {
        // Rows from 2023 on a clock pinned to 2023: nothing is stale, whatever today's date is.
        val store = store()
        store.insert(listOf(observation("evt-a", createdAt = epoch))).getOrThrow()
        store.insert(listOf(observation("evt-b", createdAt = epoch))).getOrThrow()

        assertEquals(listOf("evt-a", "evt-b"), events())
        assertTrue(store.getPruningRecords().getOrThrow().isEmpty())
    }

    // ==================== Expiry ====================

    @Test
    fun `an EXPIRES_AT row in the past is gone and one in the future is not`() = runBlocking<Unit> {
        val store = store()
        store.insert(
            listOf(
                observation("evt-expired", createdAt = epoch, decay = Decay.ExpiresAt(epoch + 1.hours)),
                observation("evt-expires-now", createdAt = epoch, decay = Decay.ExpiresAt(epoch + 2.hours)),
                observation("evt-valid", createdAt = epoch, decay = Decay.ExpiresAt(epoch + 3.hours)),
                observation("evt-half-life", createdAt = epoch, decay = Decay.HalfLife(1.hours)),
            ),
        ).getOrThrow()

        clock.current = epoch + 2.hours
        store.insert(listOf(observation("evt-trigger", createdAt = clock.current))).getOrThrow()

        // A row is gone from `at` onward; a half-life never expires, only the age cap ends it.
        assertEquals(listOf("evt-half-life", "evt-valid", "evt-trigger"), events())
        val pass = store.getPruningRecords().getOrThrow().single()
        assertEquals(0L, pass.dropped_by_age)
        assertEquals(2L, pass.dropped_by_expiry)
        assertEquals(0L, pass.dropped_by_cap)
    }

    // ==================== Cap ====================

    @Test
    fun `a store over MAX_ROWS is cut to PRUNE_TO_ROWS oldest-first`() = runBlocking<Unit> {
        val store = store(maxRows = 10, pruneToRows = 6, sweepIntervalInserts = 100)
        // The first insert sweeps whatever it finds; spend it on nothing so the rows below can pile up.
        store.insert(emptyList()).getOrThrow()
        val rows = (1..12).map { observation("evt-%02d".format(it), createdAt = epoch + it.hours) }
        // Interleave sensitivity so the oldest-first order is visibly not a sensitivity order.
        store.insert(
            rows.mapIndexed { index, row ->
                row.copy(sensitivity = if (index % 2 == 0) Sensitivity.PROTECTED else Sensitivity.PUBLIC)
            },
        ).getOrThrow()
        assertEquals(12L, rowCount())

        // Still under the interval: no sweep yet.
        store.insert(listOf(observation("evt-13", createdAt = epoch + 13.hours))).getOrThrow()
        assertEquals(13L, rowCount())

        val sweeping = store(maxRows = 10, pruneToRows = 6)
        sweeping.insert(listOf(observation("evt-14", createdAt = epoch + 14.hours))).getOrThrow()

        assertEquals((9..14).map { "evt-%02d".format(it) }, events())
        val pass = sweeping.getPruningRecords().getOrThrow().single()
        assertEquals(8L, pass.dropped_by_cap)
        assertEquals(14L, pass.rows_before)
        assertEquals(6L, pass.rows_after)
    }

    @Test
    fun `a store at MAX_ROWS exactly is not cut`() = runBlocking<Unit> {
        val store = store(maxRows = 3, pruneToRows = 2, sweepIntervalInserts = 100)
        store.insert(emptyList()).getOrThrow()
        store.insert((1..3).map { observation("evt-$it", createdAt = epoch + it.hours) }).getOrThrow()

        store(maxRows = 3, pruneToRows = 2).insert(emptyList()).getOrThrow()

        assertEquals(3L, rowCount())
    }

    // ==================== The marker and the interval ====================

    @Test
    fun `the pruning table carries one row per pass that dropped something`() = runBlocking<Unit> {
        val store = store()
        store.insert(listOf(observation("evt-old", createdAt = epoch))).getOrThrow()
        // Nothing to drop: no marker.
        store.insert(listOf(observation("evt-young", createdAt = epoch + 1.hours))).getOrThrow()
        assertTrue(store.getPruningRecords().getOrThrow().isEmpty())

        clock.current = epoch + ObservationBudget.MAX_AGE + 30.minutes
        store.insert(listOf(observation("evt-a", createdAt = clock.current))).getOrThrow()
        clock.current = epoch + ObservationBudget.MAX_AGE + 2.hours
        store.insert(listOf(observation("evt-b", createdAt = clock.current))).getOrThrow()

        val passes = store.getPruningRecords().getOrThrow()
        assertEquals(2, passes.size)
        // Newest first, like EventStorePruning.
        val (second, first) = passes
        assertEquals((epoch + ObservationBudget.MAX_AGE + 30.minutes).toEpochMilliseconds(), first.pruned_at)
        assertEquals(1L, first.dropped_by_age)
        assertEquals(3L, first.rows_before)
        assertEquals(2L, first.rows_after)
        assertEquals(1L, second.dropped_by_age)
        assertEquals(3L, second.rows_before)
        assertEquals(2L, second.rows_after)
    }

    @Test
    fun `the first insert sweeps and then every SWEEP_INTERVAL_INSERTS rows`() = runBlocking<Unit> {
        // Rows that would be swept are already present when this store opens the database.
        store(sweepIntervalInserts = 100).insert(listOf(observation("evt-old", createdAt = epoch))).getOrThrow()
        clock.current = epoch + ObservationBudget.MAX_AGE + 1.days

        val store = store(sweepIntervalInserts = 3)
        store.insert(emptyList()).getOrThrow()
        assertEquals(0L, rowCount(), "the first insert sweeps unconditionally, even an empty one")

        store.insert(listOf(observation("evt-1", createdAt = epoch))).getOrThrow()
        store.insert(listOf(observation("evt-2", createdAt = epoch))).getOrThrow()
        assertEquals(2L, rowCount(), "two rows since the sweep is under the interval of three")

        store.insert(listOf(observation("evt-3", createdAt = epoch))).getOrThrow()
        assertEquals(0L, rowCount(), "the third row crosses the interval and the sweep runs")
    }

    @Test
    fun `a re-stored perceive does not count toward the interval`() = runBlocking<Unit> {
        clock.current = epoch + ObservationBudget.MAX_AGE + 1.days
        val store = store(sweepIntervalInserts = 2)
        store.insert(emptyList()).getOrThrow()
        store.insert(listOf(observation("evt-1", createdAt = epoch))).getOrThrow()

        repeat(3) { store.insert(listOf(observation("evt-1", createdAt = epoch))).getOrThrow() }

        assertEquals(1L, rowCount(), "writes that added nothing bring no sweep closer")
    }

    // ==================== Forgetting ====================

    @Test
    fun `forgetSubject removes every holder's rows for that subject and nothing else`() = runBlocking<Unit> {
        val store = store()
        store.insert(
            listOf(
                observation("evt-1", epoch, subject = "canon/link/ada", holder = holder),
                observation("evt-2", epoch, subject = "canon/link/ada", holder = other),
                observation("evt-3", epoch, subject = "canon/link/harbor", holder = holder),
            ),
        ).getOrThrow()

        assertEquals(2, store.forgetSubject(Ref<Subject>("canon/link/ada")).getOrThrow())

        assertEquals(listOf("evt-3"), events())
        assertEquals(0, store.forgetSubject(Ref<Subject>("canon/link/ada")).getOrThrow())
    }

    @Test
    fun `forgetHolder removes everything one holder observed and nothing else`() = runBlocking<Unit> {
        val store = store()
        store.insert(
            listOf(
                observation("evt-1", epoch, subject = "canon/link/ada", holder = holder),
                observation("evt-2", epoch, subject = "canon/link/ada", holder = other),
                observation("evt-3", epoch, subject = "canon/link/harbor", holder = holder),
            ),
        ).getOrThrow()

        assertEquals(2, store.forgetHolder(holder).getOrThrow())

        assertEquals(listOf("evt-2"), events())
    }
}
