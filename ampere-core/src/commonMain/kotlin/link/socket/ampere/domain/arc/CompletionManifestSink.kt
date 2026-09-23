package link.socket.ampere.domain.arc

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.ArcRunEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

/**
 * Where a cancelled or failed run's [CompletionManifest] is persisted (AMPR-359): the production
 * `completionManifestSink` for [AmpereRuntime]. Pass [record].
 *
 * ### Where the manifest lives
 *
 * In the event store, as an [ArcRunEvent.CompletionManifestRecorded] published through
 * [AgentEventApi] — persisted under the run's id, then dispatched — rather than in a table of its
 * own. The event store is already migrated on every platform and indexed by `run_id`, and an
 * `ArcRunTrace` is already a fold of it, so the manifest lands in the same trace as the phases,
 * model invocations and Watt cost of the run it closes out: `ArcRunTrace.completion`. Live
 * observers see it on the bus too.
 *
 * ### Teardown rules
 *
 * The runtime calls [record] under `NonCancellable` while the run is being torn down — for a run
 * whose caller was cancelled, it is the only place the record can land — and swallows anything it
 * throws. So [record] stays bounded, and never reports a failure by throwing:
 * - in size: the manifest is reduced to a [CompletionRecord] before it is written;
 * - in time: a write still pending after [timeout] is abandoned. The timeout bounds every
 *   suspension on the way; the SQLite write itself cannot be interrupted, and is bounded by the
 *   driver's busy timeout instead — so a write abandoned mid-statement may still land.
 *
 * A write that fails, or that is abandoned before it confirms, is logged and counted in
 * [failures]; a manifest that may be lost shows up there and in the log, never as a changed
 * outcome.
 */
@OptIn(ExperimentalAtomicApi::class)
class CompletionManifestSink internal constructor(
    private val publish: suspend (ArcRunEvent.CompletionManifestRecorded) -> Result<*>,
    private val eventSource: EventSource,
    private val clock: Clock,
    private val timeout: Duration,
    private val logger: EventLogger,
) {
    /**
     * @param eventApi The door the manifest goes through: persisted by its repository, stamped by
     *   its clock, attributed to its agent id — [DEFAULT_AGENT_ID] unless the host has its own.
     * @param timeout How long a write may take before it is abandoned.
     * @param logger Where a manifest that could not be confirmed persisted is reported.
     */
    constructor(
        eventApi: AgentEventApi,
        timeout: Duration = DEFAULT_TIMEOUT,
        logger: EventLogger = ConsoleEventLogger(),
    ) : this(
        publish = { event -> eventApi.publish(event, runId = event.runId) },
        eventSource = EventSource.Agent(eventApi.agentId),
        clock = eventApi.clock,
        timeout = timeout,
        logger = logger,
    )

    private val failureCount = AtomicInt(0)

    /**
     * How many manifests this sink could not confirm as persisted: writes that failed, and writes
     * abandoned at [timeout] — which may still have landed.
     */
    val failures: Int
        get() = failureCount.load()

    /** Persist [manifest]. Returns once it is written, or once the write has failed or timed out. */
    suspend fun record(manifest: CompletionManifest) {
        val written: Result<*>? = try {
            withTimeoutOrNull(timeout) {
                publish(manifest.toEvent(eventSource = eventSource, timestamp = clock.now()))
            }
        } catch (e: CancellationException) {
            // The runtime calls this under NonCancellable, where this cannot be the run's own
            // cancellation. A direct caller being cancelled is owed the rethrow.
            throw e
        } catch (e: Throwable) {
            Result.failure<Unit>(e)
        }

        val problem = when {
            written == null -> "the write was abandoned after $timeout, and may or may not have landed"
            written.isFailure -> "the write failed"
            else -> return
        }
        failureCount.incrementAndFetch()
        logger.logError(
            message = "Completion manifest for Arc run ${manifest.runId} was not confirmed persisted: $problem",
            throwable = written?.exceptionOrNull(),
        )
    }

    companion object {
        /** The agent id a host's runtime publishes manifests under, when it has no id of its own. */
        const val DEFAULT_AGENT_ID: String = "ampere.arc-runtime"

        /** How long [record] waits on the store before abandoning a write. */
        val DEFAULT_TIMEOUT: Duration = 2.seconds
    }
}
