package link.socket.ampere.cli.ondevice

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * Spawns, supervises and talks to `ampere-helper` (AMPR-375).
 *
 * ## Spawned in its own process group
 *
 * The helper is a subprocess that could outlive its caller, so it is spawned through
 * [ProcessGroups] like every such process (`docs/concepts/cancellation-address.md`), and its
 * [CancellationAddress] is held from the moment it starts ([address]). Stopping it, on [close] and
 * whenever a request misses its deadline, is SIGTERM to the group, a bounded wait, then SIGKILL:
 * [GroupedProcess.terminate], never `Process.destroy()`, which on the bash spawn path ends only the
 * wrapper.
 *
 * The address is held in memory and deliberately not written anywhere. Persisting an address is
 * for work that must be reaped after its supervisor dies, and this work must not survive its
 * supervisor at all. The helper sees to that itself: stdin reaching EOF ends it, however this JVM
 * died.
 *
 * ## One request at a time
 *
 * A request is written only once the one before it has been answered, timed out or ended. The
 * model is one resource, two generations at once would compete for it on a machine already short
 * of memory, and the helper answers in order anyway. A probe asked during a generation waits
 * behind it.
 *
 * ## Every request is bounded, and a dead helper is a reason
 *
 * Each request carries a deadline its caller chose. A helper that misses one has its group
 * terminated: a generation past its deadline still holds the model, and nothing queued behind it
 * would ever be answered. The next request starts a fresh helper, and so does the next request
 * after the helper exits on its own, so a crash costs the request in flight and nothing after it.
 *
 * Nothing here throws for a helper that cannot start, has exited, is late or is too old. Each is an
 * [AmpereHelperException] inside the [Result], and [health] keeps the reason. A helper that speaks
 * another protocol version, or does not serve inference, is [AmpereHelperHealth.Incompatible] for
 * the rest of this process's life: nothing short of rebuilding it will change that.
 *
 * @param binary The helper executable.
 * @param probeTimeout How long a freshly spawned helper gets to answer `probe`.
 * @param spawn Starts [binary]. The default spawns it through [ProcessGroups]; a test may count spawns.
 */
internal class AmpereHelperProcess(
    private val binary: File,
    private val probeTimeout: Duration = DEFAULT_PROBE_TIMEOUT,
    private val spawn: (File) -> GroupedProcess = { file -> ProcessGroups.start(ProcessBuilder(file.absolutePath)) },
) : AutoCloseable {

    private val requestLock = Mutex()
    private val nextId = AtomicLong()
    private val readers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<AmpereHelperHealth>(AmpereHelperHealth.Installed)

    @Volatile
    private var live: Session? = null

    @Volatile
    private var closed = false

    /** An orderly JVM exit that nobody called [close] for still ends the helper's group. */
    private val shutdownHook = Thread({ endSession("the JVM is shutting down") }, "ampere-helper-shutdown")
        .also { hook -> runCatching { Runtime.getRuntime().addShutdownHook(hook) } }

    /** What this helper can do right now, readable without making a call. */
    val health: StateFlow<AmpereHelperHealth> = state.asStateFlow()

    /** Where to send "stop" for the live helper and everything in its group; null when none is running. */
    val address: CancellationAddress?
        get() = live?.grouped?.address

    /**
     * One request and its reply, on the live helper or a freshly spawned one, within [timeout].
     *
     * Never throws for the helper's sake: an absent, exited, late, incompatible or refusing helper is
     * an [AmpereHelperException] inside the [Result]. Cancellation propagates as cancellation, and a
     * request cancelled on the wire ends the helper, as a missed deadline does.
     */
    suspend fun <T> call(
        op: String,
        params: JsonElement?,
        serializer: DeserializationStrategy<T>,
        timeout: Duration,
    ): Result<T> = requestLock.withLock {
        val session = liveSession().getOrElse { return@withLock Result.failure(it) }
        exchange(session, op, params, timeout).mapCatching { frame -> decode(op, frame.result, serializer) }
    }

    /** Ends the helper for good. Idempotent; no call spawns anything afterwards. */
    override fun close() {
        closed = true
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        endSession("the helper was closed")
        readers.cancel()
    }

    // ---- the process -----------------------------------------------------------------------

    /**
     * The live, validated session, spawning and validating one if there is none. Runs under
     * [requestLock], so two callers that both find no helper start one between them.
     */
    private suspend fun liveSession(): Result<Session> {
        (state.value as? AmpereHelperHealth.Incompatible)?.let { incompatible ->
            return Result.failure(AmpereHelperException.Incompatible(incompatible.reason))
        }
        if (closed) return Result.failure(AmpereHelperException.Unreachable(CLOSED))

        live?.let { existing ->
            if (existing.validated && !existing.ended.isCompleted && existing.grouped.process.isAlive) {
                return Result.success(existing)
            }
            val exit = withContext(Dispatchers.IO) { existing.exitDescription() }
            end(existing, "it had exited$exit")
        }

        // Not cancellable: a cancellation that landed mid-spawn would otherwise leave a helper
        // running that nothing holds. Once it is [live], a later cancellation ends it.
        val grouped = try {
            withContext(NonCancellable + Dispatchers.IO) { spawn(binary) }
        } catch (failure: Exception) {
            val reason = "the helper at ${binary.path} could not be started: ${failure.message}"
            state.value = AmpereHelperHealth.Broken(reason)
            return Result.failure(AmpereHelperException.Unreachable(reason, failure))
        }

        val session = Session(grouped)
        live = session
        if (closed) {
            end(session, CLOSED)
            return Result.failure(AmpereHelperException.Unreachable(CLOSED))
        }
        readers.launch { read(session) }
        readers.launch { drainErrors(session) }
        return validate(session)
    }

    /**
     * `probe` on a fresh session, before anything else is asked of it. That is what makes "too old"
     * a state the caller reads rather than a surprise in the middle of a generation.
     */
    private suspend fun validate(session: Session): Result<Session> {
        val frame = exchange(session, AmpereHelperWire.Op.PROBE, params = null, timeout = probeTimeout)
            .getOrElse { failure ->
                if (failure is AmpereHelperException.Refused) {
                    // It answered, and not as a helper answers its probe: not a binary this build can use.
                    return incompatible(
                        session,
                        "the program at ${binary.path} refused the helper's probe: ${failure.message}",
                    )
                }
                return Result.failure(failure)
            }

        val probed = try {
            decode(AmpereHelperWire.Op.PROBE, frame.result, ProbeResult.serializer())
        } catch (_: AmpereHelperException) {
            return incompatible(session, "the program at ${binary.path} does not answer a probe as the helper does")
        }

        val build = probed.build.ifEmpty { "an unnamed build" }
        when {
            !probed.speaks(AmpereHelperWire.VERSION) -> return incompatible(
                session,
                "the helper ($build) speaks protocol ${probed.protocolMin}–${probed.protocolMax}; " +
                    "this build needs ${AmpereHelperWire.VERSION}",
            )
            AmpereHelperWire.INFERENCE_SERVICE !in probed.services -> return incompatible(
                session,
                "the helper ($build) serves ${probed.services}; " +
                    "this build needs '${AmpereHelperWire.INFERENCE_SERVICE}'",
            )
        }

        session.validated = true
        state.value = AmpereHelperHealth.Ready(build = probed.build, address = session.grouped.address)
        return Result.success(session)
    }

    private suspend fun incompatible(session: Session, reason: String): Result<Session> {
        end(session, reason)
        state.value = AmpereHelperHealth.Incompatible(reason)
        return Result.failure(AmpereHelperException.Incompatible(reason))
    }

    // ---- the wire --------------------------------------------------------------------------

    private suspend fun exchange(
        session: Session,
        op: String,
        params: JsonElement?,
        timeout: Duration,
    ): Result<HelperFrame> {
        val id = nextId.incrementAndGet().toString()
        val reply = CompletableDeferred<Awaited>()
        session.pending[id] = reply
        // The reader marks the stream's end before completing what is pending, so a request
        // registered after it finished sees the end here instead of waiting out its deadline.
        if (session.ended.isCompleted) reply.complete(Awaited.Ended)

        val line = AmpereHelperWire.json.encodeToString(
            HelperRequest.serializer(),
            HelperRequest(v = AmpereHelperWire.VERSION, id = id, op = op, params = params),
        )
        val awaited = try {
            withContext(Dispatchers.IO) {
                session.writer.write(line)
                session.writer.newLine()
                session.writer.flush()
            }
            withTimeoutOrNull(timeout) { reply.await() } ?: Awaited.TimedOut
        } catch (cancellation: CancellationException) {
            // The caller stopped waiting with the request on the wire. The helper answers in order,
            // so a request left running would hold every later one behind it: end it the way a
            // missed deadline does.
            end(session, "the caller stopped waiting for '$op'")
            throw cancellation
        } catch (failure: IOException) {
            end(session, "its input closed")
            return Result.failure(AmpereHelperException.Unreachable("the helper stopped accepting requests", failure))
        } finally {
            session.pending.remove(id)
        }

        return when (awaited) {
            is Awaited.Replied ->
                if (awaited.frame.ok == true) {
                    Result.success(awaited.frame)
                } else {
                    Result.failure(refusal(session, op, awaited.frame.error))
                }

            Awaited.Ended -> {
                val exit = withContext(Dispatchers.IO) { session.exitDescription() }
                end(session, "it exited while serving '$op'$exit")
                Result.failure(AmpereHelperException.Unreachable("the helper exited while serving '$op'$exit"))
            }

            Awaited.TimedOut -> {
                // Killed rather than left running: a stalled generation will not finish because we
                // waited longer, and everything after it would wait behind it.
                end(session, "'$op' got no answer within $timeout")
                Result.failure(
                    AmpereHelperException.Unreachable("no answer to '$op' within $timeout", timedOut = true),
                )
            }
        }
    }

    private suspend fun refusal(session: Session, op: String, error: HelperWireError?): AmpereHelperException {
        val code = error?.code ?: AmpereHelperWire.ErrorCode.PLATFORM_REFUSED
        val message = error?.message ?: "the helper refused '$op' without saying why"
        if (code == AmpereHelperWire.ErrorCode.UNSUPPORTED_PROTOCOL) {
            // Terminal: a helper that cannot speak this version will not learn to before it is rebuilt.
            end(session, message)
            state.value = AmpereHelperHealth.Incompatible(message)
            return AmpereHelperException.Incompatible(message)
        }
        return AmpereHelperException.Refused(op = op, code = code, reason = message)
    }

    private fun <T> decode(op: String, result: JsonElement?, serializer: DeserializationStrategy<T>): T {
        if (result == null) throw AmpereHelperException.Unreachable("the helper answered '$op' with no result")
        return try {
            AmpereHelperWire.json.decodeFromJsonElement(serializer, result)
        } catch (failure: IllegalArgumentException) {
            throw AmpereHelperException.Unreachable(
                "the helper's '$op' reply could not be read: ${failure.message}",
                failure,
            )
        }
    }

    /**
     * Every line the helper printed, until it stops. When the stream ends, everything pending is
     * completed as ended, so each caller reports the exit rather than waiting out its deadline: the
     * stream ending is the answer, and it is a different answer from a deadline passing.
     */
    private fun read(session: Session) {
        try {
            session.grouped.process.inputStream.bufferedReader().forEachLine { line -> route(line, session) }
        } catch (_: IOException) {
            // The stream closing under the read is how a terminated helper ends it.
        } finally {
            session.ended.complete(Unit)
            session.pending.values.forEach { it.complete(Awaited.Ended) }
        }
    }

    private fun route(line: String, session: Session) {
        if (line.isBlank()) return
        val frame = runCatching { AmpereHelperWire.json.decodeFromString(HelperFrame.serializer(), line) }
            .getOrNull()
            ?: return
        // A reply nobody waits for answers a request whose deadline already passed.
        frame.id?.let { session.pending[it] }?.complete(Awaited.Replied(frame))
    }

    /** Nothing is expected on stderr, so its last lines are what a crash said, kept for the reason. */
    private fun drainErrors(session: Session) {
        runCatching { session.grouped.process.errorStream.bufferedReader().forEachLine(session::noteError) }
    }

    private fun endSession(reason: String) {
        live?.let { discard(it, reason) }
    }

    private suspend fun end(session: Session, reason: String) {
        withContext(NonCancellable + Dispatchers.IO) { discard(session, reason) }
    }

    /** Blocking: ends [session]'s process group and waits, briefly, for it to go. */
    private fun discard(session: Session, reason: String) {
        if (live === session) {
            live = null
            state.update { current ->
                if (current is AmpereHelperHealth.Incompatible) current else AmpereHelperHealth.Broken(reason)
            }
        }
        session.pending.values.forEach { it.complete(Awaited.Ended) }
        // EOF first. The helper exits the moment its input closes, so the signal that follows
        // usually finds the group already gone.
        runCatching { session.writer.close() }
        session.grouped.terminate(TERMINATION_GRACE)
    }

    private class Session(val grouped: GroupedProcess) {
        val writer: BufferedWriter = grouped.process.outputStream.bufferedWriter()
        val pending = ConcurrentHashMap<String, CompletableDeferred<Awaited>>()
        val ended = CompletableDeferred<Unit>()

        /** Set once `probe` has passed. A session is reused only after that. */
        @Volatile
        var validated: Boolean = false

        private val errors = ArrayDeque<String>()

        fun noteError(line: String) {
            if (line.isBlank()) return
            synchronized(errors) {
                errors.addLast(line.take(MAX_ERROR_LINE))
                while (errors.size > MAX_ERROR_LINES) errors.removeFirst()
            }
        }

        /** `" (exit 134: what it last said)"`, or `""` while it is still running. Blocks briefly. */
        fun exitDescription(): String {
            val process = grouped.process
            if (!process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) return ""
            val said = synchronized(errors) { errors.lastOrNull() }
            return if (said == null) " (exit ${process.exitValue()})" else " (exit ${process.exitValue()}: $said)"
        }
    }

    /** How a request ended. Told apart because each gets its own reason and its own remedy. */
    private sealed interface Awaited {
        data class Replied(val frame: HelperFrame) : Awaited

        /** The helper's output closed with this request outstanding: it exited. */
        data object Ended : Awaited

        /** The deadline passed with the helper still running. */
        data object TimedOut : Awaited
    }

    companion object {
        /** How long a fresh helper gets to answer `probe`, the one deadline that belongs to no op's caller. */
        val DEFAULT_PROBE_TIMEOUT: Duration = 10.seconds

        /**
         * How long a group that has been asked to stop gets before SIGKILL. Short, and rarely spent:
         * the pipe is closed first and the helper exits on EOF.
         */
        private val TERMINATION_GRACE: Duration = 2.seconds

        private const val CLOSED = "the helper was closed"
        private const val EXIT_WAIT_MS = 200L
        private const val MAX_ERROR_LINES = 5
        private const val MAX_ERROR_LINE = 300
    }
}

/**
 * Why a call on the helper produced no reply. Never thrown by [AmpereHelperProcess]: always the
 * failure inside a [Result], so the engine words it for its own callers.
 */
internal sealed class AmpereHelperException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** No helper running, one that stopped, or one that did not answer in time. The next call may do better. */
    class Unreachable(
        reason: String,
        cause: Throwable? = null,
        val timedOut: Boolean = false,
    ) : AmpereHelperException(reason, cause)

    /** A helper this build cannot use: another protocol version, or no inference service. */
    class Incompatible(
        reason: String,
    ) : AmpereHelperException(reason)

    /** The helper took the request and answered with an error frame; [message] is its own. */
    class Refused(
        val op: String,
        val code: String,
        reason: String,
    ) : AmpereHelperException(reason)
}

/** What the helper is, as far as this process knows. Drives the engine's probe and its reasons. */
internal sealed interface AmpereHelperHealth {
    val reason: String?

    /** On disk and not yet spoken to. */
    data object Installed : AmpereHelperHealth {
        override val reason: String? get() = null
    }

    /** Alive and compatible. [address] is a new one on every respawn. */
    data class Ready(
        val build: String,
        val address: CancellationAddress,
    ) : AmpereHelperHealth {
        override val reason: String? get() = null
    }

    /** It was there and stopped being. The next call spawns a fresh one. */
    data class Broken(override val reason: String) : AmpereHelperHealth

    /** It speaks another protocol, or serves no inference. Terminal until the helper is rebuilt. */
    data class Incompatible(override val reason: String) : AmpereHelperHealth
}
