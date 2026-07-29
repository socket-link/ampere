package link.socket.ampere.domain.arc.bridge

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.EmissionProvenance
import link.socket.ampere.agents.domain.emission.ProseFormat
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcOutcome
import link.socket.ampere.domain.arc.OrchestrationConfig
import link.socket.ampere.domain.arc.OrchestrationType
import link.socket.ampere.domain.arc.TerminationReason
import okio.Path.Companion.toPath

/**
 * The Swift-facing Arc execution bridge (AMPR-243).
 *
 * Real dispatchers throughout: the whole point of the bridge is that `start` / `observe` /
 * `cancel` are called from *outside* the run's coroutine, on another thread, which a
 * single-threaded test dispatcher cannot express. These are the same assertions the Swift
 * harness makes across the Objective-C boundary — proved here first, where a failure is legible.
 */
class ArcSessionTest {

    private val timeoutMillis = 60_000L

    // ---- fixtures ----------------------------------------------------------------------

    /** A temp dir with the AGENTS.md/README.md that ChargePhase requires to produce a context. */
    private fun arcProjectDir(prefix: String): java.nio.file.Path {
        val tempDir = createTempDirectory(prefix)
        tempDir.resolve("README.md").writeText("# BridgeProject\n\nA test project for the Arc bridge.")
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use suspend functions

            ## Architecture
            - Clean architecture
            """.trimIndent(),
        )
        return tempDir
    }

    private fun arcConfig(name: String) = ArcConfig(
        name = name,
        agents = listOf(ArcAgentConfig(role = "code")),
        orchestration = OrchestrationConfig(
            type = OrchestrationType.SEQUENTIAL,
            order = listOf("code"),
        ),
    )

    private fun progressEvent(runId: String, text: String): EmissionEvent.BaseProduced =
        EmissionEvent.BaseProduced(
            eventId = "evt-$text",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("code"),
            urgency = Urgency.MEDIUM,
            emission = Emission(
                id = "emission-$text",
                kind = EmissionKind.Prose,
                payload = EmissionPayload.Prose(text = text, format = ProseFormat.PLAIN),
                provenance = EmissionProvenance(runId = runId, inputDigest = "digest-$text"),
                producedAt = Clock.System.now(),
            ),
        )

    private fun proseOf(emission: Emission): String =
        (emission.payload as EmissionPayload.Prose).text

    /** Suspend until Flow has taken at least one tick, so a cancel here is genuinely mid-flight. */
    private suspend fun awaitFlowUnderway(runtime: AmpereRuntime) {
        withTimeout(timeoutMillis) {
            while ((runtime.flowPhase?.getCurrentTick() ?: 0) < 1) {
                delay(5)
            }
        }
    }

    /** The session's own coroutines are cancelled asynchronously; give them a moment to detach. */
    private suspend fun awaitNoLiveCoroutines(scope: CoroutineScope, except: Set<Any> = emptySet()) {
        withTimeout(timeoutMillis) {
            while (scope.coroutineContext.job.children.any { it !in except }) {
                delay(5)
            }
        }
    }

    // ---- tests -------------------------------------------------------------------------

    @Test
    fun `observe delivers progress emissions and cancel halts the run cooperatively`() = runBlocking<Unit> {
        val projectDir = arcProjectDir("bridge-cancel")
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val bus = EventSerialBus(scope = callerScope)
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-cancel-arc"),
                projectDir = projectDir.toString().toPath(),
                agentScope = callerScope,
                // Large enough that Flow cannot finish before the cancel lands.
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

            val handle = session.start("Implement a very long running goal")

            val seen = Channel<Emission>(Channel.UNLIMITED)
            val finished = CompletableDeferred<Unit>()
            handle.observe(
                onEmission = { seen.trySend(it) },
                onFinished = { finished.complete(Unit) },
            )

            awaitFlowUnderway(runtime)

            repeat(3) { index -> bus.publish(progressEvent(handle.runId, "progress-$index")) }

            // A set, not a list: the bus launches each handler as its own coroutine, so events
            // published back-to-back can be delivered in either order. The bridge inherits that
            // and does not pretend otherwise.
            val delivered = withTimeout(timeoutMillis) { List(3) { proseOf(seen.receive()) }.toSet() }
            assertEquals(setOf("progress-0", "progress-1", "progress-2"), delivered)

            val outcome = withTimeout(timeoutMillis) { assertIs<ArcOutcome.Cancelled>(handle.cancel()) }

            assertEquals(handle.runId, outcome.runId)
            assertNotNull(outcome.chargeResult, "Charge finished, so it should be reported")
            assertEquals(
                TerminationReason.CANCELLED,
                outcome.flowResult?.terminationReason,
                "A cancelled Flow must report CANCELLED, not a fallback reason",
            )
            assertFalse(runtime.isRunning())
            assertFalse(handle.isActive)

            // The observation completes on its own when the run ends: a Swift AsyncStream needs
            // this to call finish() instead of hanging its `for await` loop forever.
            withTimeout(timeoutMillis) { finished.await() }

            // Nothing the session started outlives the run — no pump, no watcher, no observer.
            awaitNoLiveCoroutines(callerScope)
        } finally {
            callerScope.cancel()
        }
    }

    @Test
    fun `cancel is idempotent and keeps returning the same outcome`() = runBlocking<Unit> {
        val projectDir = arcProjectDir("bridge-idempotent")
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val bus = EventSerialBus(scope = callerScope)
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-idempotent-arc"),
                projectDir = projectDir.toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

            val handle = session.start("Implement a very long running goal")
            awaitFlowUnderway(runtime)

            val first = withTimeout(timeoutMillis) { handle.cancel() }
            val second = withTimeout(timeoutMillis) { handle.cancel() }
            val awaited = withTimeout(timeoutMillis) { handle.await() }

            assertIs<ArcOutcome.Cancelled>(first)
            assertSame(first, second, "A second cancel must report the first one's outcome")
            assertSame(first, awaited, "await must agree with cancel")
        } finally {
            callerScope.cancel()
        }
    }

    @Test
    fun `cancelling before the run is dispatched still halts it`() = runBlocking<Unit> {
        // The stop button can be pressed in the same breath as the start button. The runtime
        // holds no cancellable job until execute() is under way, so this races that window
        // deliberately — it must not run the Arc to completion.
        val projectDir = arcProjectDir("bridge-early-cancel")
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val bus = EventSerialBus(scope = callerScope)
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-early-cancel-arc"),
                projectDir = projectDir.toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

            val handle = session.start("Implement a very long running goal")
            val outcome = withTimeout(timeoutMillis) { handle.cancel() }

            assertIs<ArcOutcome.Cancelled>(outcome)
            assertFalse(runtime.isRunning())
        } finally {
            callerScope.cancel()
        }
    }

    @Test
    fun `a run that completes reports Completed and finishes its observers`() = runBlocking<Unit> {
        val projectDir = arcProjectDir("bridge-complete")
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val bus = EventSerialBus(scope = callerScope)
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-complete-arc"),
                projectDir = projectDir.toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = 1,
            )
            val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

            val handle = session.start("Add a health check endpoint")

            val finished = CompletableDeferred<Unit>()
            handle.observe(onEmission = { }, onFinished = { finished.complete(Unit) })

            val outcome = withTimeout(timeoutMillis) { assertIs<ArcOutcome.Completed>(handle.await()) }
            assertEquals(handle.runId, outcome.runId)

            withTimeout(timeoutMillis) { finished.await() }
            awaitNoLiveCoroutines(callerScope)

            // No projection was supplied, so there is no ledger to fold — and the bridge says so
            // rather than inventing one.
            assertNull(handle.trace())
        } finally {
            callerScope.cancel()
        }
    }

    @Test
    fun `an observer cancelled by the consumer stops receiving without disturbing the others`() =
        runBlocking<Unit> {
            val projectDir = arcProjectDir("bridge-observer")
            val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            try {
                val bus = EventSerialBus(scope = callerScope)
                val runtime = AmpereRuntime(
                    arcConfig = arcConfig("bridge-observer-arc"),
                    projectDir = projectDir.toString().toPath(),
                    agentScope = callerScope,
                    maxFlowTicks = Int.MAX_VALUE,
                )
                val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

                val handle = session.start("Implement a very long running goal")
                awaitFlowUnderway(runtime)

                val leaving = Channel<Emission>(Channel.UNLIMITED)
                val staying = Channel<Emission>(Channel.UNLIMITED)
                val leavingToken = handle.observe { leaving.trySend(it) }
                handle.observe { staying.trySend(it) }

                bus.publish(progressEvent(handle.runId, "before"))
                assertEquals("before", withTimeout(timeoutMillis) { proseOf(leaving.receive()) })
                assertEquals("before", withTimeout(timeoutMillis) { proseOf(staying.receive()) })

                leavingToken.cancel()
                assertTrue(leavingToken.isCancelled)

                bus.publish(progressEvent(handle.runId, "after"))
                assertEquals("after", withTimeout(timeoutMillis) { proseOf(staying.receive()) })
                assertNull(leaving.tryReceive().getOrNull(), "A cancelled observer must go quiet")

                withTimeout(timeoutMillis) { handle.cancel() }
            } finally {
                callerScope.cancel()
            }
        }

    @Test
    fun `a late observer is caught up from the replay buffer`() = runBlocking<Unit> {
        // Swift attaches its progress stream in a `Task { }` on the line after `start`, which is
        // a scheduling hop later. The opening of the run must not fall through that gap.
        val projectDir = arcProjectDir("bridge-replay")
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val bus = EventSerialBus(scope = callerScope)
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-replay-arc"),
                projectDir = projectDir.toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

            val handle = session.start("Implement a very long running goal")

            bus.publish(progressEvent(handle.runId, "opening"))

            val late = Channel<Emission>(Channel.UNLIMITED)
            handle.observe { late.trySend(it) }

            assertEquals("opening", withTimeout(timeoutMillis) { proseOf(late.receive()) })

            withTimeout(timeoutMillis) { handle.cancel() }
        } finally {
            callerScope.cancel()
        }
    }

    @Test
    fun `create builds a session that owns its own scope and bus`() = runBlocking<Unit> {
        // The Swift-facing entry point: nothing in its signature is a coroutine type, because
        // kotlinx.coroutines does not cross the Objective-C boundary. Exercised here so a change
        // that reintroduces a CoroutineScope parameter fails on the JVM first.
        val projectDir = arcProjectDir("bridge-owned-scope")
        val session = ArcSession.create(
            arcConfig = arcConfig("bridge-owned-arc"),
            projectDirPath = projectDir.toString(),
            maxFlowTicks = 1,
        )

        try {
            val handle = session.start("Add a health check endpoint")

            val finished = CompletableDeferred<Unit>()
            handle.observe(onEmission = { }, onFinished = { finished.complete(Unit) })

            val outcome = withTimeout(timeoutMillis) { assertIs<ArcOutcome.Completed>(handle.await()) }
            assertEquals(handle.runId, outcome.runId)
            withTimeout(timeoutMillis) { finished.await() }
        } finally {
            session.close()
        }

        // close() is idempotent, so a Swift deinit can call it without tracking whether it ran.
        session.close()
    }

    @Test
    fun `start rejects a blank goal and a runtime that is already running`() = runBlocking<Unit> {
        val projectDir = arcProjectDir("bridge-guards")
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val bus = EventSerialBus(scope = callerScope)
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-guards-arc"),
                projectDir = projectDir.toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

            assertFailsWithMessage<IllegalArgumentException>("User goal cannot be blank") {
                session.start("   ")
            }

            val handle = session.start("Implement a very long running goal")
            awaitFlowUnderway(runtime)

            assertFailsWithMessage<IllegalStateException>("Runtime is already executing") {
                session.start("A second goal")
            }

            withTimeout(timeoutMillis) { handle.cancel() }
        } finally {
            callerScope.cancel()
        }
    }

    private inline fun <reified T : Throwable> assertFailsWithMessage(
        expected: String,
        block: () -> Unit,
    ) {
        val thrown = kotlin.test.assertFailsWith<T> { block() }
        assertEquals(expected, thrown.message)
    }
}
