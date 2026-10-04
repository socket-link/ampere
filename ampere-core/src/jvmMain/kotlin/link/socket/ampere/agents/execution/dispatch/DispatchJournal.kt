package link.socket.ampere.agents.execution.dispatch

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.SupervisorEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * The supervisor's claim-record journal: append-only, crash-safe, one file per
 * supervisor instance (AMPR-291 mechanism M-A, built in AMPR-307).
 *
 * ### What it is for
 *
 * A SIGKILLed supervisor leaves work-source claims that no timeout will clear and
 * agent subprocesses that keep running — editing worktrees, spending tokens, able to
 * push branches and open merge requests. Nothing could enumerate them, because no
 * process id was persisted anywhere. This journal is the input every "for each
 * in-flight dispatch…" recovery check needs. It stores facts and nothing else: when
 * to spawn, when to kill, and what to do with a suspect dispatch are the caller's
 * decisions (`Switchboard`, and the startup reconciliation pass of AMPR-291
 * mechanism M-C).
 *
 * ### On-disk shape
 *
 * `<directory>/<instanceId>.jsonl` — JSON Lines, one [DispatchRecord] per line
 * under a `dispatch` discriminator, optionally ending in a `clean-shutdown` marker:
 *
 * ```
 * {"kind":"dispatch","record":{"ticketId":"AMPR-1","phase":"CLAIMED",...}}
 * {"kind":"dispatch","record":{"ticketId":"AMPR-1","phase":"AGENT_RUNNING",...}}
 * {"kind":"clean-shutdown","at":"2026-10-04T12:00:00Z"}
 * ```
 *
 * ### Why a whole-file rewrite
 *
 * Every write is staged to a temp file in the same directory, fsynced, and
 * `ATOMIC_MOVE`d over the journal. An in-place append is never used, so a reader can
 * never observe a half-written record: it sees the file either before or after the
 * rename, and both are complete. The cost is rewriting the file per append, which is
 * the right trade for a file holding tens of records per supervisor run.
 *
 * A reader is nonetheless defensive — [readAll] quarantines any line it cannot parse
 * rather than dropping it or throwing — because atomicity is a property of this
 * writer, not of the file. A journal from a build that appended in place, a
 * filesystem that did not honour the rename, or a corrupted block all produce a line
 * that must be reported rather than silently skipped.
 *
 * **Durability limit:** the fsync is of the staged file, not of the directory, which
 * the JVM cannot portably open for sync. A host crash in the window between the
 * rename and the directory entry reaching the device can therefore lose the most
 * recent record — never a partial one. Process death (SIGKILL included) cannot: the
 * rename has already happened in the page cache.
 *
 * ### Ownership
 *
 * One file per instance is what makes the whole-file rewrite safe: two supervisors
 * sharing a file would clobber each other's records. [instanceId] must therefore be
 * unique per supervisor process, and this object must be the only writer for it. The
 * clean-shutdown marker is per file as a consequence, which is exactly the question
 * recovery asks — *did the supervisor that claimed this exit gracefully?*
 *
 * @see link.socket.ampere.agents.execution.process.ProcessGroups for the other half
 *   of the substrate: spawning the agent in its own process group so the
 *   [CancellationAddress][link.socket.ampere.agents.execution.process.CancellationAddress]
 *   recorded here can stop it after a restart.
 */
class DispatchJournal private constructor(
    /** The supervisor process this journal belongs to. */
    val instanceId: String,
    /** The journal file, `<directory>/<instanceId>.jsonl`. Created on first append. */
    val file: Path,
    private val eventApi: AgentEventApi?,
    private val eventSource: EventSource,
    private val now: () -> Instant,
    private val idGenerator: () -> String,
) {

    /** Serializes writers within the process; across processes, see "Ownership". */
    private val mutex = Mutex()

    /**
     * The journal's lines exactly as they are on disk.
     *
     * Raw strings rather than parsed records so that a quarantined line survives a
     * rewrite byte-for-byte: parsing on read and re-encoding on write would silently
     * drop a line nothing could parse, which is the one thing this type must not do.
     */
    private val lines = mutableListOf<String>()

    private var cleanShutdownMarked = false

    /**
     * Quarantined lines this journal has already announced.
     *
     * A line stays unparseable until something rewrites it, and a recovery pass may
     * read the directory more than once — so reporting per read would turn one bad
     * line into an unbounded stream of events about it. Said once per journal, then
     * quiet, exactly as `AgentEventApi` does for an undecodable store row.
     */
    private val reportedQuarantines = mutableSetOf<String>()

    private val quarantineReportLock = Mutex()

    /** The directory holding this and every other instance's journal. */
    val directory: Path get() = file.parent

    /**
     * Append [record] and make it durable before returning.
     *
     * Call this *before* the dispatch does the thing the record describes — a record
     * written after the agent is spawned is a record that a crash in between loses.
     *
     * @throws IllegalArgumentException if [record] names a different supervisor instance.
     * @throws IllegalStateException if [markCleanShutdown] has already been called.
     */
    suspend fun append(record: DispatchRecord) {
        require(record.supervisorInstanceId == instanceId) {
            "Record for ${record.ticketId} names supervisor instance " +
                "'${record.supervisorInstanceId}', but this journal is '$instanceId'"
        }
        mutex.withLock {
            check(!cleanShutdownMarked) {
                "Journal '$instanceId' is closed: a record cannot follow its clean-shutdown marker"
            }
            commit(JSON.encodeToString(JournalLine.serializer(), JournalLine.Dispatch(record)))
            // Published under the lock so the event order on the bus is the line order
            // in the file. An append is once per phase transition, so the contention
            // this costs is nil and the alternative is a trace that reorders a lifecycle.
            publish(
                SupervisorEvent.DispatchRecorded(
                    eventId = idGenerator(),
                    eventSource = eventSource,
                    timestamp = now(),
                    instanceId = instanceId,
                    ticketId = record.ticketId,
                    phase = record.phase,
                    processGroupId = record.agentAddress?.processGroupId,
                    mergeRequestUrl = record.mergeRequestUrl,
                ),
            )
        }
    }

    /**
     * Write the clean-shutdown marker: this supervisor exited gracefully, so none of
     * its dispatches is suspect.
     *
     * Idempotent. After it returns, [append] fails — the marker is the last line of
     * the file by definition, and a record after it is a line [readAll] quarantines.
     */
    suspend fun markCleanShutdown() {
        mutex.withLock {
            if (cleanShutdownMarked) return@withLock
            commit(JSON.encodeToString(JournalLine.serializer(), JournalLine.CleanShutdown(now())))
            cleanShutdownMarked = true
            publish(
                SupervisorEvent.CleanShutdownMarked(
                    eventId = idGenerator(),
                    eventSource = eventSource,
                    timestamp = now(),
                    instanceId = instanceId,
                    dispatchCount = parse(instanceId, lines).latestPerDispatch().size,
                ),
            )
        }
    }

    /**
     * Read this instance's journal back from disk, reporting anything unparseable.
     *
     * Reads the file rather than the in-memory copy, so it reflects what a restarted
     * supervisor would see. Every quarantined line is published as a
     * [SupervisorEvent.JournalLineQuarantined].
     */
    suspend fun readAll(): JournalRead = read(instanceId, file)

    /**
     * Read every supervisor instance's journal in [directory], this one included.
     *
     * The entry point for the startup reconciliation pass: the reads whose
     * [JournalRead.cleanShutdown] is false are the supervisors that died, and their
     * [JournalRead.suspectDispatches] are the claims and process groups still owed
     * something. Ordered by instance id so the output is stable.
     *
     * Only `*.jsonl` files are read; a staged temp file left behind by a killed
     * writer is never parsed.
     */
    suspend fun scanAll(): List<JournalRead> {
        val files = withContext(Dispatchers.IO) {
            if (!Files.isDirectory(directory)) {
                emptyList()
            } else {
                Files.newDirectoryStream(directory, "*.$EXTENSION").use { stream ->
                    stream.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }
                }
            }
        }
        return files.map { read(it.fileName.toString().removeSuffix(".$EXTENSION"), it) }
    }

    private suspend fun read(owner: String, journalFile: Path): JournalRead {
        val raw = withContext(Dispatchers.IO) { readRawLines(journalFile) }
        return parse(owner, raw).also { result ->
            result.quarantined.forEach { line ->
                if (firstReportOf(owner, line)) {
                    publish(
                        SupervisorEvent.JournalLineQuarantined(
                            eventId = idGenerator(),
                            eventSource = eventSource,
                            timestamp = now(),
                            instanceId = owner,
                            lineNumber = line.lineNumber,
                            reason = line.reason,
                        ),
                    )
                }
            }
        }
    }

    /** True the first time this journal sees [line] in [owner]'s journal file. */
    private suspend fun firstReportOf(owner: String, line: QuarantinedLine): Boolean =
        quarantineReportLock.withLock { reportedQuarantines.add("$owner:${line.lineNumber}:${line.raw}") }

    private suspend fun publish(event: SupervisorEvent) {
        eventApi?.publish(event)
    }

    /** Stage, fsync and rename the journal with [line] added, then record it in memory. */
    private suspend fun commit(line: String) {
        val updated = lines + line
        withContext(Dispatchers.IO) { writeAtomically(updated) }
        lines += line
    }

    private fun writeAtomically(contents: List<String>) {
        Files.createDirectories(directory)
        val staged = Files.createTempFile(directory, tempPrefix(instanceId), TEMP_SUFFIX)
        try {
            FileOutputStream(staged.toFile()).use { out ->
                val writer = out.writer(StandardCharsets.UTF_8).buffered()
                contents.forEach { writer.append(it).append('\n') }
                writer.flush()
                // The rename is atomic whatever happens, but it is only durable once
                // the bytes it will point at have reached the device.
                out.fd.sync()
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(staged) }
            throw e
        }
    }

    companion object {

        /** Attribution for journal events when the caller does not name a source. */
        const val DEFAULT_SOURCE_ID: String = "ampere.dispatch-journal"

        /** The journal's file extension; JSON Lines, one record per line. */
        const val EXTENSION: String = "jsonl"

        /** How much of an unparseable line [JournalRead.quarantined] keeps verbatim. */
        const val MAX_QUARANTINED_CHARS: Int = 512

        private const val TEMP_SUFFIX = ".tmp"

        /**
         * Instance ids become file names, so they are restricted rather than escaped:
         * no separators, no `..`, and no leading `.` (which is how a staged write is
         * named, and how it stays out of the `*.jsonl` scan).
         */
        private val SAFE_INSTANCE_ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")

        private val JSON = Json {
            prettyPrint = false
            encodeDefaults = true
            classDiscriminator = "kind"
            // Lenient about fields it does not know, strict about structure: a newer
            // Ampere's extra field must not make an older reader quarantine a whole
            // journal, while a truncated line still fails to parse and is reported.
            ignoreUnknownKeys = true
        }

        /**
         * Open the journal for [instanceId] under [directory], creating the directory
         * if needed and reading back anything already there.
         *
         * Pass an [eventApi] to make the journal's writes visible on the
         * `EventSerialBus`; left null, the journal is silent and purely durable.
         * [eventSource], [now] and [idGenerator] are injected so a test can pin what a
         * published event carries — wiring is manual here as everywhere in Ampere.
         *
         * Leftover staged writes for *this* instance are swept, which is safe because
         * this process is its only writer. Other instances' files are left untouched.
         *
         * @param directory the supervisor's own state directory — conventionally
         *   `~/.ampere/supervisor/journal/`, never `~/.ampere/Workspaces` (AMPR-300).
         * @throws IllegalArgumentException if [instanceId] is not usable as a file name.
         */
        fun open(
            directory: Path,
            instanceId: String,
            eventApi: AgentEventApi? = null,
            eventSource: EventSource = EventSource.Agent(DEFAULT_SOURCE_ID),
            now: () -> Instant = { Clock.System.now() },
            idGenerator: () -> String = { generateUUID() },
        ): DispatchJournal {
            require(SAFE_INSTANCE_ID.matches(instanceId)) {
                "Supervisor instance id '$instanceId' is not usable as a file name: " +
                    "expected 1-128 characters of [A-Za-z0-9._-], not starting with '.'"
            }
            Files.createDirectories(directory)
            val file = directory.resolve("$instanceId.$EXTENSION")
            sweepStagedWrites(directory, instanceId)

            val journal = DispatchJournal(instanceId, file, eventApi, eventSource, now, idGenerator)
            journal.lines += readRawLines(file)
            journal.cleanShutdownMarked = parse(instanceId, journal.lines).cleanShutdown
            return journal
        }

        /**
         * Parse journal [raw] lines into records, the clean-shutdown marker, and
         * whatever could not be read.
         *
         * Pure, and the only place a line becomes a record. Blank lines are skipped —
         * a record is never empty, so one carries nothing to quarantine.
         */
        internal fun parse(instanceId: String, raw: List<String>): JournalRead {
            val entries = mutableListOf<DispatchRecord>()
            val quarantined = mutableListOf<QuarantinedLine>()
            var cleanShutdownAt: Instant? = null

            for ((index, text) in raw.withIndex()) {
                val lineNumber = index + 1
                if (text.isBlank()) continue

                val line = try {
                    JSON.decodeFromString(JournalLine.serializer(), text)
                } catch (e: IllegalArgumentException) {
                    // SerializationException is an IllegalArgumentException, and so is
                    // the one Instant.parse throws, so this one catch covers both a
                    // truncated line and a well-formed line with a bad field.
                    quarantined += QuarantinedLine(
                        lineNumber = lineNumber,
                        raw = text.take(MAX_QUARANTINED_CHARS),
                        reason = e.message?.lineSequence()?.first() ?: "unparseable line",
                    )
                    continue
                }

                if (cleanShutdownAt != null) {
                    // The marker is the last line by construction, so anything after it
                    // means two writers shared the file or it was edited. Treating such
                    // a record as live would let a "clean" journal hide an orphan.
                    quarantined += QuarantinedLine(
                        lineNumber = lineNumber,
                        raw = text.take(MAX_QUARANTINED_CHARS),
                        reason = "line follows the clean-shutdown marker",
                    )
                    continue
                }

                when (line) {
                    is JournalLine.Dispatch -> entries += line.record
                    is JournalLine.CleanShutdown -> cleanShutdownAt = line.at
                }
            }

            return JournalRead(instanceId, entries, cleanShutdownAt, quarantined)
        }

        /**
         * Read [file] as lines, tolerating bytes that are not valid UTF-8.
         *
         * `Files.readAllLines` throws on malformed input, and a record truncated in the
         * middle of a multi-byte character is exactly that — so the whole journal would
         * become unreadable because of one bad line. Decoding with replacement turns
         * that line into one that fails to parse, which is a line this type can report.
         */
        private fun readRawLines(file: Path): List<String> {
            if (!Files.isRegularFile(file)) return emptyList()
            return String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .split('\n')
                .dropLastWhile { it.isEmpty() }
        }

        /** Delete staged writes a killed writer left behind for [instanceId]. */
        private fun sweepStagedWrites(directory: Path, instanceId: String) {
            runCatching {
                Files.newDirectoryStream(directory, "${tempPrefix(instanceId)}*$TEMP_SUFFIX").use { stream ->
                    stream.forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }

        private fun tempPrefix(instanceId: String): String = ".$instanceId.$EXTENSION."
    }
}

/**
 * What one supervisor instance's journal says, as read back from disk.
 *
 * @property instanceId The supervisor the journal belongs to.
 * @property entries Every record that parsed, in append order — so a dispatch with
 *   several phases appears several times.
 * @property cleanShutdownAt When the supervisor marked a graceful exit, or null if it
 *   never did.
 * @property quarantined Lines that could not be read as records. Non-empty means
 *   something was written that nothing can now interpret.
 */
data class JournalRead(
    val instanceId: String,
    val entries: List<DispatchRecord>,
    val cleanShutdownAt: Instant?,
    val quarantined: List<QuarantinedLine>,
) {

    /** Whether the supervisor that wrote this journal exited gracefully. */
    val cleanShutdown: Boolean get() = cleanShutdownAt != null

    /**
     * The most recent record for each dispatch, keyed by
     * [DispatchRecord.ticketId] — the current state of every dispatch the journal saw.
     */
    fun latestPerDispatch(): Map<String, DispatchRecord> = entries.associateBy { it.ticketId }

    /**
     * Dispatches that may still hold a claim or a running agent process group.
     *
     * Empty when the supervisor shut down cleanly: it released what it held. Otherwise
     * every dispatch whose latest phase is not [DispatchPhase.isTerminal], ordered by
     * ticket id. What to *do* about them — release the claim, terminate the group, adopt
     * the worktree — is the reconciliation pass's decision, not this module's.
     */
    fun suspectDispatches(): List<DispatchRecord> =
        if (cleanShutdown) {
            emptyList()
        } else {
            latestPerDispatch().values
                .filterNot { it.phase.isTerminal }
                .sortedBy { it.ticketId }
        }
}

/**
 * A journal line that could not be read as a record, kept rather than dropped.
 *
 * @property lineNumber 1-based position in the file, so the line can be found.
 * @property raw The line verbatim, truncated to [DispatchJournal.MAX_QUARANTINED_CHARS].
 * @property reason Why it did not parse.
 */
data class QuarantinedLine(
    val lineNumber: Int,
    val raw: String,
    val reason: String,
)

/**
 * One line of the journal file. The `kind` discriminator is what lets the
 * clean-shutdown marker share the stream with the records it terminates.
 */
@Serializable
private sealed interface JournalLine {

    @Serializable
    @SerialName("dispatch")
    data class Dispatch(val record: DispatchRecord) : JournalLine

    @Serializable
    @SerialName("clean-shutdown")
    data class CleanShutdown(val at: Instant) : JournalLine
}
