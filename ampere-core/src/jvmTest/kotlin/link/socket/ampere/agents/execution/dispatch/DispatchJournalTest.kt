package link.socket.ampere.agents.execution.dispatch

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.SupervisorEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.execution.process.CancellationAddress

/**
 * The claim-record journal's contract (AMPR-307, AMPR-291 mechanism M-A).
 *
 * Three properties carry the weight, because recovery is built on them: a record
 * that round-trips, a clean-shutdown marker whose *absence* is what makes a dispatch
 * suspect, and a line nothing can parse being reported rather than dropped or thrown.
 * `DispatchJournalKillTest` covers the fourth — that a real SIGKILL mid-append cannot
 * produce a torn line in the first place.
 */
class DispatchJournalTest {

    private val recordedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val directories = mutableListOf<Path>()
    private var nextEventId = 0

    @AfterTest
    fun tearDown() {
        scope.cancel()
        directories.forEach { it.toFile().deleteRecursively() }
    }

    // `add`, not `+=`: a Path is itself an Iterable<Path>, so `+=` is ambiguous between
    // the element and the collection overload.
    private fun tempDir(): Path =
        Files.createTempDirectory("ampere-journal").also { directories.add(it) }

    /** Every journal under test reads time from [recordedAt], so assertions can name it. */
    private fun journal(
        dir: Path,
        instanceId: String = INSTANCE,
        eventApi: AgentEventApi? = null,
    ) = DispatchJournal.open(
        directory = dir,
        instanceId = instanceId,
        eventApi = eventApi,
        eventSource = EventSource.Agent("supervisor"),
        now = { recordedAt },
        idGenerator = { "journal-event-${nextEventId++}" },
    )

    private fun record(
        ticketId: String,
        instanceId: String = INSTANCE,
        phase: DispatchPhase = DispatchPhase.CLAIMED,
        address: CancellationAddress? = null,
        mergeRequestUrl: String? = null,
    ) = DispatchRecord(
        ticketId = ticketId,
        supervisorInstanceId = instanceId,
        worktreePath = "/tmp/worktrees/$ticketId",
        branchName = "miley/${ticketId.lowercase()}",
        phase = phase,
        recordedAt = recordedAt,
        agentAddress = address,
        mergeRequestUrl = mergeRequestUrl,
    )

    private fun journalFile(dir: Path, instanceId: String = INSTANCE): Path =
        dir.resolve("$instanceId.${DispatchJournal.EXTENSION}")

    /** Writes [lines] as the journal file for [INSTANCE], newline-terminated. */
    private fun seed(dir: Path, lines: List<String>) {
        Files.write(
            journalFile(dir),
            lines.joinToString(separator = "\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8),
        )
    }

    /** The journal's own encoding of [record], so a fixture cannot drift from the schema. */
    private suspend fun encoded(record: DispatchRecord): String {
        val staging = tempDir()
        journal(staging, record.supervisorInstanceId).append(record)
        return Files.readAllLines(journalFile(staging, record.supervisorInstanceId)).single()
    }

    @Test
    fun `a record round-trips through the journal`() = runBlocking<Unit> {
        val dir = tempDir()
        val address = CancellationAddress(leaderPid = 4242, processGroupId = 4242, leaderStartedAt = recordedAt)
        val written = record(
            ticketId = "AMPR-307",
            phase = DispatchPhase.MR_OPENED,
            address = address,
            mergeRequestUrl = "https://github.com/socket-link/ampere/pull/721",
        )

        journal(dir).append(written)

        val read = journal(dir).readAll()
        assertEquals(listOf(written), read.entries)
        assertEquals(address, read.entries.single().agentAddress, "the cancellation address is the recovery handle")
        assertTrue(read.quarantined.isEmpty())
        assertFalse(read.cleanShutdown)
    }

    @Test
    fun `every phase of the dispatch lifecycle survives the round-trip`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        DispatchPhase.entries.forEach { journal.append(record("AMPR-${it.ordinal}", phase = it)) }

        val read = journal(dir).readAll()

        assertEquals(DispatchPhase.entries.toList(), read.entries.map { it.phase })
        assertTrue(read.quarantined.isEmpty())
    }

    @Test
    fun `the latest record for a dispatch is the one that counts`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        journal.append(record("AMPR-1", phase = DispatchPhase.CLAIMED))
        journal.append(record("AMPR-1", phase = DispatchPhase.AGENT_RUNNING))
        journal.append(record("AMPR-2", phase = DispatchPhase.CLAIMED))

        val read = journal.readAll()

        assertEquals(3, read.entries.size, "the journal is append-only — nothing is rewritten")
        assertEquals(
            mapOf("AMPR-1" to DispatchPhase.AGENT_RUNNING, "AMPR-2" to DispatchPhase.CLAIMED),
            read.latestPerDispatch().mapValues { it.value.phase },
        )
    }

    @Test
    fun `without a clean-shutdown marker every unfinished dispatch is suspect`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        journal.append(record("AMPR-1", phase = DispatchPhase.AGENT_RUNNING))
        journal.append(record("AMPR-2", phase = DispatchPhase.DONE))
        journal.append(record("AMPR-3", phase = DispatchPhase.ESCALATED))
        journal.append(record("AMPR-4", phase = DispatchPhase.VERIFYING))

        val read = journal(dir).readAll()

        assertFalse(read.cleanShutdown)
        assertEquals(listOf("AMPR-1", "AMPR-4"), read.suspectDispatches().map { it.ticketId })
    }

    @Test
    fun `a clean-shutdown marker clears every dispatch`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        journal.append(record("AMPR-1", phase = DispatchPhase.AGENT_RUNNING))
        journal.markCleanShutdown()

        val read = journal(dir).readAll()

        assertTrue(read.cleanShutdown)
        assertEquals(recordedAt, read.cleanShutdownAt)
        assertTrue(
            read.suspectDispatches().isEmpty(),
            "a supervisor that exited gracefully released what it held",
        )
        assertEquals(1, read.entries.size, "the marker is not a record")
    }

    @Test
    fun `marking a clean shutdown twice is a no-op`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        journal.append(record("AMPR-1"))
        journal.markCleanShutdown()
        journal.markCleanShutdown()

        assertEquals(2, Files.readAllLines(journalFile(dir)).size)
    }

    @Test
    fun `a record cannot follow the clean-shutdown marker`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        journal.markCleanShutdown()

        assertIs<IllegalStateException>(runCatching { journal.append(record("AMPR-1")) }.exceptionOrNull())

        // And the refusal survives a restart: the marker is on disk, not just in memory.
        assertIs<IllegalStateException>(
            runCatching { journal(dir).append(record("AMPR-1")) }.exceptionOrNull(),
        )
    }

    @Test
    fun `a record naming a different supervisor instance is refused`() = runBlocking<Unit> {
        val journal = journal(tempDir())

        val failure = runCatching {
            journal.append(record("AMPR-1", instanceId = "some-other-supervisor"))
        }.exceptionOrNull()

        assertIs<IllegalArgumentException>(failure)
        assertTrue(failure.message!!.contains("some-other-supervisor"))
    }

    @Test
    fun `an instance id that is not usable as a file name is refused`() {
        val dir = tempDir()
        listOf("", ".", "..", "../escape", "a/b", ".hidden", "has space", "a".repeat(129)).forEach { id ->
            assertFailsWith<IllegalArgumentException>("'$id' must not become a journal file name") {
                DispatchJournal.open(dir, id)
            }
        }
    }

    @Test
    fun `a truncated final record is quarantined and the records before it still read`() = runBlocking<Unit> {
        val dir = tempDir()
        val torn = encoded(record("AMPR-2")).dropLast(40)
        seed(dir, listOf(encoded(record("AMPR-1")), torn))

        val read = journal(dir).readAll()

        assertEquals(listOf("AMPR-1"), read.entries.map { it.ticketId })
        val quarantined = read.quarantined.single()
        assertEquals(2, quarantined.lineNumber)
        assertEquals(torn, quarantined.raw)
        assertTrue(quarantined.reason.isNotBlank(), "a quarantine with no reason is not diagnosable")
    }

    @Test
    fun `a record truncated inside a multi-byte character does not make the journal unreadable`() = runBlocking<Unit> {
        val dir = tempDir()
        val good = encoded(record("AMPR-1"))
        // "AMPR-✂" ends in a three-byte character; cutting into it leaves a byte
        // sequence `Files.readAllLines` would refuse to decode at all.
        val torn = encoded(record("AMPR-✂")).toByteArray(StandardCharsets.UTF_8).dropLast(5).toByteArray()
        Files.write(journalFile(dir), (good + "\n").toByteArray(StandardCharsets.UTF_8) + torn)

        val read = journal(dir).readAll()

        assertEquals(listOf("AMPR-1"), read.entries.map { it.ticketId })
        assertEquals(2, read.quarantined.single().lineNumber)
    }

    @Test
    fun `a record after the clean-shutdown marker is quarantined rather than read as live`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        journal.append(record("AMPR-1", phase = DispatchPhase.AGENT_RUNNING))
        journal.markCleanShutdown()
        // Two writers on one file, or a hand-edit: a dispatch recorded past the marker
        // would otherwise be hidden behind a journal that reads as clean.
        Files.write(
            journalFile(dir),
            (encoded(record("AMPR-2", phase = DispatchPhase.AGENT_RUNNING)) + "\n")
                .toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND,
        )

        val read = journal(dir).readAll()

        assertEquals(listOf("AMPR-1"), read.entries.map { it.ticketId })
        assertEquals("line follows the clean-shutdown marker", read.quarantined.single().reason)
    }

    @Test
    fun `reopening a journal preserves a line it could not parse`() = runBlocking<Unit> {
        val dir = tempDir()
        val torn = encoded(record("AMPR-2")).dropLast(40)
        seed(dir, listOf(encoded(record("AMPR-1")), torn))

        journal(dir).append(record("AMPR-3"))

        val read = journal(dir).readAll()
        assertEquals(listOf("AMPR-1", "AMPR-3"), read.entries.map { it.ticketId })
        assertEquals(
            torn,
            read.quarantined.single().raw,
            "a rewrite must not launder away the evidence that something is unreadable",
        )
    }

    @Test
    fun `appends leave no staged file behind`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)
        repeat(5) { journal.append(record("AMPR-$it")) }

        assertEquals(listOf("$INSTANCE.${DispatchJournal.EXTENSION}"), dir.toFile().list()!!.sorted())
        assertEquals(5, Files.readAllLines(journalFile(dir)).size)
    }

    @Test
    fun `opening a journal sweeps staged writes a killed writer left behind`() {
        val dir = tempDir()
        val mine = Files.createFile(dir.resolve(".$INSTANCE.${DispatchJournal.EXTENSION}.4242.tmp"))
        val theirs = Files.createFile(dir.resolve(".other.${DispatchJournal.EXTENSION}.4242.tmp"))

        journal(dir)

        assertFalse(Files.exists(mine), "our own staged writes are ours to clean up")
        assertTrue(Files.exists(theirs), "another instance's staged write may still be in flight")
    }

    @Test
    fun `scanAll reads every instance and ignores anything that is not a journal`() = runBlocking<Unit> {
        val dir = tempDir()
        journal(dir, "supervisor-a")
            .append(record("AMPR-1", instanceId = "supervisor-a", phase = DispatchPhase.AGENT_RUNNING))
        journal(dir, "supervisor-b").apply {
            append(record("AMPR-2", instanceId = "supervisor-b"))
            markCleanShutdown()
        }
        Files.createFile(dir.resolve(".supervisor-c.${DispatchJournal.EXTENSION}.1.tmp"))
        Files.write(dir.resolve("notes.txt"), "not a journal".toByteArray())

        val reads = journal(dir, "recovery").scanAll()

        assertEquals(listOf("supervisor-a", "supervisor-b"), reads.map { it.instanceId })
        assertEquals(listOf("AMPR-1"), reads.flatMap { it.suspectDispatches() }.map { it.ticketId })
        assertTrue(reads.all { it.quarantined.isEmpty() })
    }

    @Test
    fun `journal writes are observable on the bus`() = runBlocking<Unit> {
        val dir = tempDir()
        InMemoryEventApi.open(agentId = "supervisor", scope = scope).use { door ->
            val observed = Channel<SupervisorEvent>(Channel.UNLIMITED)
            listOf(
                SupervisorEvent.DispatchRecorded.EVENT_TYPE,
                SupervisorEvent.CleanShutdownMarked.EVENT_TYPE,
            ).forEach { type ->
                door.bus.subscribe<SupervisorEvent, Subscription>("observer", type) { event, _ ->
                    observed.send(event)
                }
            }
            val journal = journal(dir, eventApi = door.api)

            journal.append(
                record(
                    "AMPR-307",
                    phase = DispatchPhase.AGENT_RUNNING,
                    address = CancellationAddress(leaderPid = 99, processGroupId = 99, leaderStartedAt = recordedAt),
                ),
            )
            journal.markCleanShutdown()

            // Handlers are dispatched asynchronously, so take both and match by type
            // rather than asserting an arrival order the bus does not promise.
            val events = withTimeout(10.seconds) { List(2) { observed.receive() } }

            val recorded = assertIs<SupervisorEvent.DispatchRecorded>(
                events.single { it is SupervisorEvent.DispatchRecorded },
            )
            assertEquals("AMPR-307", recorded.ticketId)
            assertEquals(DispatchPhase.AGENT_RUNNING, recorded.phase)
            assertEquals(99, recorded.processGroupId)
            assertEquals(INSTANCE, recorded.instanceId)
            assertEquals(recordedAt, recorded.timestamp)

            val marked = assertIs<SupervisorEvent.CleanShutdownMarked>(
                events.single { it is SupervisorEvent.CleanShutdownMarked },
            )
            assertEquals(1, marked.dispatchCount)

            // Through the door, so the trace has it too and not only a live subscriber.
            assertEquals(
                1,
                door.repository.getEventsByType(SupervisorEvent.DispatchRecorded.EVENT_TYPE).getOrThrow().size,
            )
        }
    }

    @Test
    fun `a quarantined line is reported on the bus`() = runBlocking<Unit> {
        val dir = tempDir()
        seed(dir, listOf(encoded(record("AMPR-1")).dropLast(30)))

        InMemoryEventApi.open(agentId = "supervisor", scope = scope).use { door ->
            val journal = journal(dir, eventApi = door.api)

            assertEquals(1, journal.readAll().quarantined.size)
            // The line stays unreadable, so a second read must not announce it again.
            assertEquals(1, journal.readAll().quarantined.size)

            val reported = door.repository
                .getEventsByType(SupervisorEvent.JournalLineQuarantined.EVENT_TYPE)
                .getOrThrow()
            val event = assertIs<SupervisorEvent.JournalLineQuarantined>(
                reported.singleOrNull() ?: error("expected one report, got ${reported.size}"),
            )
            assertEquals(INSTANCE, event.instanceId)
            assertEquals(1, event.lineNumber)
            assertTrue(event.reason.isNotBlank())
        }
    }

    @Test
    fun `a journal with no event api is silent and still durable`() = runBlocking<Unit> {
        val dir = tempDir()
        val journal = journal(dir)

        journal.append(record("AMPR-1"))

        assertEquals(listOf("AMPR-1"), journal.readAll().entries.map { it.ticketId })
        assertEquals(dir, journal.directory)
    }

    private companion object {
        const val INSTANCE = "supervisor-under-test"
    }
}
