package link.socket.ampere.observe

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonPerson
import link.socket.ampere.canon.CanonPlace
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Decay
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.field.observe.environmentRef
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.PerceivePage
import link.socket.ampere.plug.spi.PerceiveQuery
import link.socket.ampere.plug.spi.RecordedPerception
import link.socket.ampere.room.FixedClock

/**
 * [SqlDelightObservationStore] (AMPR-447, F7): the Environment branch's rows on
 * `JdbcSqliteDriver(IN_MEMORY)`, brought to the current schema the way every JVM open is, and
 * written the way production writes them — through [RecordedPerception.store].
 *
 * The store's clock is pinned a day after the rows are recorded, so the budget sweep every
 * insert may run (AMPR-465, [ObservationBudgetTest]) leaves these rows alone.
 */
class ObservationStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SqlDelightObservationStore

    private val linkId = LinkId("people-link")
    private val observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val holder = Ref<Subject>(HOLDER)
    private val other = Ref<Subject>(OTHER)

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseSchemaManager.ensure(driver).getOrThrow()
        store = SqlDelightObservationStore(Database(driver), clock = FixedClock(observedAt + 1.days))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun provenance(nativeId: String, eventId: String) = CanonProvenance(
        sourceHandle = SourceHandle(linkId, sourceSystem = "test.source", nativeId = nativeId),
        observedAt = observedAt,
        perceiveEventId = eventId,
    )

    private fun person(id: String, eventId: String) =
        CanonPerson(canonId = CanonId(id), provenance = provenance(id, eventId), displayName = "Person $id")

    private fun place(id: String, eventId: String) =
        CanonPlace(canonId = CanonId(id), provenance = provenance(id, eventId), name = "Place $id")

    /** A recorded perceive as `recordedPerceive` hands it back: every entity stamped with [eventId]. */
    private fun perception(
        eventId: String,
        recordedAtMillis: Long = 1_700_000_001_000,
        entities: (String) -> List<CanonEntity>,
    ) = RecordedPerception(
        page = PerceivePage.unfiltered(PerceiveQuery(linkId = linkId), entities(eventId)),
        perceiveEventId = eventId,
        recordedAt = Instant.fromEpochMilliseconds(recordedAtMillis),
    )

    private suspend fun visibleTo(viewer: Ref<Subject>?, limit: Int = 10): List<Belief.Settled> =
        store.visibleTo(viewer, limit).getOrThrow()

    @Test
    fun `a person observation is invisible to another viewer and to replay reads`() = runBlocking<Unit> {
        val perceived = perception("evt-person") { listOf(person("ada", it)) }

        assertEquals(1, perceived.store(holder, store).getOrThrow())

        val seen = visibleTo(holder).single()
        assertEquals(Sensitivity.PROTECTED, seen.sensitivity)
        assertEquals(perceived.page.entities.single().environmentRef, seen.subject)
        assertTrue(visibleTo(other).isEmpty())
        assertTrue(visibleTo(null).isEmpty())
        assertTrue(store.replayable("evt-person").getOrThrow().isEmpty())
    }

    @Test
    fun `a private observation is visible only to its holder`() = runBlocking<Unit> {
        val perceived = perception("evt-place") { listOf(place("harbor", it)) }

        perceived.store(holder, store).getOrThrow()

        val seen = visibleTo(holder).single()
        assertEquals(Sensitivity.PRIVATE, seen.sensitivity)
        assertTrue(visibleTo(other).isEmpty())
        assertTrue(visibleTo(null).isEmpty())
        // A replay is nobody's view, but it may fold the PRIVATE rows of the perceive it replays.
        assertEquals(listOf(seen), store.replayable("evt-place").getOrThrow())
    }

    @Test
    fun `re-storing the same perceive is a no-op`() = runBlocking<Unit> {
        val perceived = perception("evt-both") { listOf(person("ada", it), place("harbor", it)) }

        assertEquals(2, perceived.store(holder, store).getOrThrow())
        val first = visibleTo(holder)
        assertEquals(0, perceived.store(holder, store).getOrThrow())

        assertEquals(first, visibleTo(holder))
        assertEquals(2, first.size)
    }

    @Test
    fun `the same perceive held by another subject is another observation`() = runBlocking<Unit> {
        val perceived = perception("evt-shared") { listOf(place("harbor", it)) }

        perceived.store(holder, store).getOrThrow()
        assertEquals(1, perceived.store(other, store).getOrThrow())

        assertEquals(listOf(holder), visibleTo(holder).map { it.holder })
        assertEquals(listOf(other), visibleTo(other).map { it.holder })
    }

    @Test
    fun `an observation reads back as it was mapped`() = runBlocking<Unit> {
        val perceived = perception("evt-roundtrip") { listOf(place("harbor", it)) }
        val mapped = perceived.observations(holder).single()

        perceived.store(holder, store).getOrThrow()

        val read = visibleTo(holder).single()
        assertEquals(mapped, read)
        assertEquals(Provenance(createdBy = "evt-roundtrip", createdAt = perceived.recordedAt), read.provenance)
    }

    @Test
    fun `every decay round-trips`() = runBlocking<Unit> {
        val decays = listOf(
            Decay.None,
            Decay.HalfLife(2.hours),
            Decay.ExpiresAt(Instant.fromEpochMilliseconds(1_800_000_000_000)),
        )
        val written = decays.mapIndexed { index, decay ->
            Belief.Settled(
                holder = holder,
                subject = Ref("canon/people-link/entity-$index"),
                content = "Observed entity-$index",
                kind = BeliefKind.OBSERVATION,
                confidence = 1.0,
                sensitivity = Sensitivity.PUBLIC,
                decay = decay,
                provenance = Provenance(createdBy = "evt-decay", createdAt = observedAt),
            )
        }

        store.insert(written).getOrThrow()

        assertEquals(decays.toSet(), visibleTo(null).map { it.decay }.toSet())
    }

    @Test
    fun `the limit applies to what the viewer may see and not to the whole table`() = runBlocking<Unit> {
        perception("evt-own", recordedAtMillis = 1_700_000_001_000) { listOf(place("own", it)) }
            .store(holder, store)
            .getOrThrow()
        // Newer than the holder's row and invisible to it: a filter after the LIMIT would hand it nothing.
        perception("evt-other", recordedAtMillis = 1_700_000_002_000) { id -> (1..3).map { place("other-$it", id) } }
            .store(other, store)
            .getOrThrow()

        assertEquals(listOf("evt-own"), visibleTo(holder, limit = 1).map { it.provenance.createdBy })
    }

    @Test
    fun `a negative limit is refused`() = runBlocking<Unit> {
        assertIs<IllegalArgumentException>(store.visibleTo(holder, limit = -1).exceptionOrNull())
    }

    @Test
    fun `a row with a decay this build cannot read is reported and not guessed`() = runBlocking<Unit> {
        driver.execute(
            null,
            "INSERT INTO ObservationStore VALUES ('$HOLDER', 'canon/x/y', 'c', 1.0, 'PRIVATE', 'SOMEDAY', 'evt-x', 0)",
            0,
        )

        val failure = assertIs<UndecodableObservationException>(store.visibleTo(holder, 10).exceptionOrNull())
        assertEquals("decay", failure.column)
        assertEquals("SOMEDAY", failure.value)
    }

    private companion object {
        const val HOLDER = "agent-scout"
        const val OTHER = "agent-other"
    }
}
