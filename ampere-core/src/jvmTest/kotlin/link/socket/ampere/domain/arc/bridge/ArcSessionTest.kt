package link.socket.ampere.domain.arc.bridge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.EmissionProvenance
import link.socket.ampere.agents.domain.emission.ProseFormat
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.domain.routing.local.OnDeviceAvailability
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcConcurrencyPolicy
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcOutcome
import link.socket.ampere.domain.arc.ArcPhase
import link.socket.ampere.domain.arc.ArcRunRejectedException
import link.socket.ampere.domain.arc.OrchestrationConfig
import link.socket.ampere.domain.arc.OrchestrationType
import link.socket.ampere.domain.arc.TerminationReason
import link.socket.ampere.llm.MissingUpstreamLlmClientException
import link.socket.ampere.trace.ModelInvocationTrace
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

    /**
     * An Arc whose one step is eligible for the device: a `ZERO` floor is the one the on-device
     * model clears. Eligibility is the Arc's declaration, not the bridge's (AMPR-372) — the
     * bridge only makes the engine reachable.
     */
    private fun onDeviceArcConfig(name: String) = ArcConfig(
        name = name,
        agents = listOf(ArcAgentConfig(role = "code", minimumRung = CapabilityRung.ZERO)),
        orchestration = OrchestrationConfig(
            type = OrchestrationType.SEQUENTIAL,
            order = listOf("code"),
        ),
    )

    private fun deviceAvailable(maxContextTokens: Int) = LocalCapacity(
        available = true,
        modelId = AIModel_OnDevice.AppleFoundationModels.name,
        maxContextTokens = maxContextTokens,
        providerId = AIProvider_OnDevice.id,
    )

    private fun deviceUnavailable(reason: String) = LocalCapacity(
        available = false,
        providerId = AIProvider_OnDevice.id,
        reason = reason,
    )

    private suspend fun ArcRunHandle.modelInvocations(): List<ModelInvocationTrace> =
        checkNotNull(trace()) { "A session with a database folds its runs' rows" }
            .phases
            .flatMap { it.modelInvocations }

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
                provenance = EmissionProvenance(
                    runId = runId,
                    inputDigest = "digest-$text",
                    parentEmissionId = null,
                    principal = Principal.Ambient,
                ),
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

    /** AMPR-359: the Swift entry point, given somewhere to keep what its runs leave behind. */
    @Test
    fun `a database-backed session keeps a cancelled run's manifest for trace to read`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
        val session = ArcSession.create(
            arcConfig = arcConfig("bridge-persist-arc"),
            projectDirPath = arcProjectDir("bridge-persist").toString(),
            maxFlowTicks = Int.MAX_VALUE,
            database = Database(driver),
        )

        try {
            val handle = session.start("Implement a very long running goal")
            val outcome = withTimeout(timeoutMillis) { assertIs<ArcOutcome.Cancelled>(handle.cancel()) }

            // `cancel` returns once the run has settled, and a cancelled run's manifest is written
            // before that — so the trace can be read straight away.
            val trace = assertNotNull(handle.trace(), "A session with a database folds its runs' rows")
            assertEquals(outcome.manifest.toRecord(), trace.completion)
        } finally {
            session.close()
            driver.close()
        }
    }

    /**
     * Closing the session cancels its run from outside, so no outcome comes back to carry the
     * manifest. The run still writes it as it unwinds.
     */
    @Test
    fun `closing a database-backed session still leaves the cancelled run's manifest`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
        val session = ArcSession.create(
            arcConfig = arcConfig("bridge-persist-close-arc"),
            projectDirPath = arcProjectDir("bridge-persist-close").toString(),
            maxFlowTicks = Int.MAX_VALUE,
            database = Database(driver),
        )

        try {
            val handle = session.start("Implement a very long running goal")
            session.close()

            // `await` returns only once the run has settled — after its manifest is written.
            val ended = withTimeout(timeoutMillis) { runCatching { handle.await() } }
            assertIs<CancellationException>(ended.exceptionOrNull(), "The caller was cancelled: no outcome")

            val completion = assertNotNull(handle.trace()?.completion)
            assertEquals(handle.runId, completion.runId)
            assertEquals(TerminationReason.CANCELLED, completion.endedBy)
            assertTrue(ArcPhase.PULSE in completion.phasesNotRun, "Pulse never ran, so no Knowledge")
        } finally {
            session.close()
            driver.close()
        }
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

            // The default declared policy is REJECT, and the refusal is typed (AMPR-284).
            val rejected = kotlin.test.assertFailsWith<ArcRunRejectedException> {
                session.start("A second goal")
            }
            assertEquals(ArcConcurrencyPolicy.REJECT, rejected.policy)
            assertEquals("bridge-guards-arc", rejected.arcName)
            assertTrue(runtime.isRunning(), "The refusal must leave the in-flight run untouched")

            withTimeout(timeoutMillis) { handle.cancel() }
        } finally {
            callerScope.cancel()
        }
    }

    /**
     * AMPR-358: a refused `start` throws; it never hands back a handle that later resolves to
     * `Failed`. Half the racers go through the bridge and half straight to `execute`, and the
     * two paths share one admission — exactly one request, from either, gets the runtime.
     */
    @Test
    fun `concurrent start and execute calls admit exactly one run`() = runBlocking<Unit> {
        val requests = 16
        val callers = Executors.newFixedThreadPool(requests).asCoroutineDispatcher()
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-atomic-arc"),
                projectDir = arcProjectDir("bridge-atomic").toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(
                scope = callerScope,
                runtime = runtime,
                eventSerialBus = EventSerialBus(scope = callerScope),
            )

            // One thread per request, all parked on the barrier, so the requests genuinely race.
            val barrier = CyclicBarrier(requests)
            val rejections = AtomicInteger()
            val attempts = List(requests) { index ->
                async(callers) {
                    barrier.await()
                    try {
                        if (index % 2 == 0) {
                            session.start("Implement a very long running goal $index")
                        } else {
                            runtime.execute("Implement a very long running goal $index")
                        }
                    } catch (e: ArcRunRejectedException) {
                        rejections.incrementAndGet()
                        e
                    }
                }
            }

            // An admitted `execute` never returns on its own; end it once every loser is refused.
            withTimeout(timeoutMillis) {
                while (rejections.get() < requests - 1 || !runtime.isRunning()) {
                    delay(5)
                }
            }
            runtime.cancel()
            val results = withTimeout(timeoutMillis) { attempts.awaitAll() }

            assertEquals(requests - 1, results.count { it is ArcRunRejectedException })
            val admitted = results.filterNot { it is ArcRunRejectedException }
            assertEquals(1, admitted.size, "Exactly one run is admitted: $results")
            val outcome = when (val winner = admitted.single()) {
                is ArcRunHandle -> withTimeout(timeoutMillis) { winner.await() }
                else -> winner
            }
            assertIs<ArcOutcome.Cancelled>(outcome, "The admitted run must end Cancelled, never Failed")
        } finally {
            callerScope.cancel()
            callers.close()
        }
    }

    @Test
    fun `concurrent starts return exactly one handle and refuse the rest synchronously`() = runBlocking<Unit> {
        val requests = 16
        val callers = Executors.newFixedThreadPool(requests).asCoroutineDispatcher()
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-atomic-start-arc"),
                projectDir = arcProjectDir("bridge-atomic-start").toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(
                scope = callerScope,
                runtime = runtime,
                eventSerialBus = EventSerialBus(scope = callerScope),
            )

            val barrier = CyclicBarrier(requests)
            val results = withTimeout(timeoutMillis) {
                List(requests) { index ->
                    async(callers) {
                        barrier.await()
                        runCatching { session.start("Implement a very long running goal $index") }
                    }
                }.awaitAll()
            }

            val handles = results.mapNotNull { it.getOrNull() }
            assertEquals(1, handles.size, "Exactly one start returns a handle: $results")
            results.mapNotNull { it.exceptionOrNull() }.forEach { assertIs<ArcRunRejectedException>(it) }

            // The one handle drives the one run: its cancel reaches the run it was issued for.
            assertIs<ArcOutcome.Cancelled>(withTimeout(timeoutMillis) { handles.single().cancel() })
            assertFalse(runtime.isRunning())
        } finally {
            callerScope.cancel()
            callers.close()
        }
    }

    /**
     * `start` claims the runtime before its run is dispatched. If the run could then be cancelled
     * before it began, nothing would release the claim and every later run would be refused.
     */
    @Test
    fun `a start on an already cancelled scope still releases its admission`() = runBlocking<Unit> {
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val runtime = AmpereRuntime(
            arcConfig = arcConfig("bridge-cancelled-scope-arc"),
            projectDir = arcProjectDir("bridge-cancelled-scope").toString().toPath(),
            agentScope = callerScope,
        )
        val session = ArcSession(
            scope = callerScope,
            runtime = runtime,
            eventSerialBus = EventSerialBus(scope = callerScope),
        )
        callerScope.cancel()

        val handle = session.start("Implement a goal that never gets to run")
        withTimeout(timeoutMillis) { runCatching { handle.await() } }

        assertFalse(handle.isActive)
        // Claimable again, so the abandoned run did give its admission back.
        runtime.releaseClaim(runtime.admitRun())
    }

    @Test
    fun `tryStart returns a refusal as a value and leaves the in-flight run untouched`() = runBlocking<Unit> {
        val projectDir = arcProjectDir("bridge-try-start")
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val bus = EventSerialBus(scope = callerScope)
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("bridge-try-start-arc"),
                projectDir = projectDir.toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = Int.MAX_VALUE,
            )
            val session = ArcSession(scope = callerScope, runtime = runtime, eventSerialBus = bus)

            assertFailsWithMessage<IllegalArgumentException>("User goal cannot be blank") {
                session.tryStart("   ")
            }

            val started = assertIs<ArcStartResult.Started>(session.tryStart("Implement a very long running goal"))
            awaitFlowUnderway(runtime)

            val rejected = assertIs<ArcStartResult.Rejected>(session.tryStart("A second goal"))
            assertEquals(ArcConcurrencyPolicy.REJECT, rejected.policy)
            assertEquals("bridge-try-start-arc", rejected.arcName)
            assertTrue(runtime.isRunning(), "The refusal must leave the in-flight run untouched")
            assertTrue(started.handle.isActive)

            val outcome = withTimeout(timeoutMillis) { started.handle.cancel() }
            assertIs<ArcOutcome.Cancelled>(outcome)
            assertEquals(started.handle.runId, outcome.runId)
        } finally {
            callerScope.cancel()
        }
    }

    // ---- on-device (AMPR-374) ----------------------------------------------------------

    /**
     * The objective end to end: an Arc started through `create` with an engine runs its
     * eligible steps on the device, the session's state shows it, and the run's trace records
     * each call under the run id with the reason the relay chose the device.
     */
    @Test
    fun `an Arc bound to an engine runs eligible steps on the device and traces them`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
        // The window a real engine reports from iOS 27 — far larger than the catalog's
        // provisional 4,096 — adopted by the binding so every Arc prompt fits.
        val engine = FakeLocalInferenceEngine(
            capacity = deviceAvailable(maxContextTokens = 32_768),
            respond = { Result.success("{}") },
        )
        val session = ArcSession.create(
            arcConfig = onDeviceArcConfig("bridge-on-device-arc"),
            projectDirPath = arcProjectDir("bridge-on-device").toString(),
            maxFlowTicks = 1,
            database = Database(driver),
            engine = engine,
            cloud = null,
        )

        try {
            val state = assertNotNull(session.onDeviceState, "An engine-bound session exposes its state")
            val observed = Channel<OnDeviceInferenceState>(Channel.UNLIMITED)
            val observation = assertNotNull(session.observeOnDeviceState { observed.trySend(it) })

            val handle = session.start("Add a health check endpoint")
            val outcome = withTimeout(timeoutMillis) { assertIs<ArcOutcome.Completed>(handle.await()) }
            assertEquals(handle.runId, outcome.runId)

            assertTrue(engine.generateCount >= 1, "The eligible step must be handed to the engine")

            val settled = withTimeout(timeoutMillis) {
                state.first { it.servedOnDevice >= engine.generateCount && !it.isInUse }
            }
            assertEquals(engine.generateCount, settled.servedOnDevice)
            assertEquals(0, settled.servedInCloud, "Nothing left the device")
            assertEquals(0, settled.failedOnDevice)
            val availability = assertIs<OnDeviceAvailability.Available>(settled.availability)
            assertEquals(32_768, availability.maxContextTokens, "The engine's own window is what the state shows")

            // The Swift-facing observation saw the same facts.
            val lastObserved = withTimeout(timeoutMillis) {
                var latest = observed.receive()
                while (latest.servedOnDevice < engine.generateCount) latest = observed.receive()
                latest
            }
            assertEquals(engine.generateCount, lastObserved.servedOnDevice)
            observation.cancel()

            // Persisted under the run id, with the relay's reason, so the trace can say where
            // each step ran.
            val invocations = handle.modelInvocations()
            assertEquals(engine.generateCount, invocations.size)
            invocations.forEach { invocation ->
                assertEquals(AIProvider_OnDevice.id, invocation.providerId)
                assertEquals(AIModel_OnDevice.AppleFoundationModels.name, invocation.modelId)
                assertEquals(true, invocation.success)
                assertFalse(invocation.routingReason.isNullOrBlank(), "A call without a routing reason is a trace gap")
            }
        } finally {
            session.close()
            driver.close()
        }
    }

    /**
     * No cloud transport and a device that cannot serve: the step fails cleanly, the prompt is
     * sent nowhere, and the state says why the device was unavailable.
     */
    @Test
    fun `an on-device only Arc sends nothing when the device cannot serve`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
        val engine = FakeLocalInferenceEngine(capacity = deviceUnavailable(LocalUnavailableReason.NOT_ENABLED))
        val session = ArcSession.create(
            arcConfig = onDeviceArcConfig("bridge-device-unavailable-arc"),
            projectDirPath = arcProjectDir("bridge-device-unavailable").toString(),
            maxFlowTicks = 1,
            database = Database(driver),
            engine = engine,
            cloud = null,
        )

        try {
            val state = assertNotNull(session.onDeviceState)
            val handle = session.start("Add a health check endpoint")

            // The step fails; the run does not. Same as a run with no transport at all.
            withTimeout(timeoutMillis) { assertIs<ArcOutcome.Completed>(handle.await()) }

            assertEquals(0, engine.generateCount, "An unavailable engine must not be given a prompt")
            assertTrue(engine.probeCount >= 1, "Availability is decided before the call, by the probe")

            // The relay wanted the device and routed around it, and said so on the bus.
            val settled = withTimeout(timeoutMillis) { state.first { it.lastFallback != null } }
            assertEquals(LocalUnavailableReason.NOT_ENABLED, settled.lastFallback?.reason)
            val availability = assertIs<OnDeviceAvailability.Unavailable>(settled.availability)
            assertEquals(LocalUnavailableReason.NOT_ENABLED, availability.reason)
            assertEquals(0, settled.servedOnDevice)
            assertEquals(0, settled.servedInCloud)
            assertFalse(settled.isInUse)

            // Every attempt is on record as a failure to find a transport — not as a call that
            // went out, and never under the on-device provider.
            val invocations = handle.modelInvocations()
            assertTrue(invocations.isNotEmpty(), "The attempt is traced, so a reader can see the step was refused")
            invocations.forEach { invocation ->
                assertEquals(false, invocation.success)
                assertEquals(MissingUpstreamLlmClientException::class.simpleName, invocation.errorType)
                assertTrue(invocation.providerId != AIProvider_OnDevice.id)
            }
        } finally {
            session.close()
            driver.close()
        }
    }

    /**
     * A prompt the engine's window cannot hold is routed away from the device before it is
     * sent — and with no cloud to route to, the step fails without the engine ever seeing it.
     * The window is the engine's own report, adopted by the binding; the Arc's prompts are
     * sized by `AgentLLMService` against it.
     */
    @Test
    fun `a prompt the device's window cannot hold is never handed to the engine`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
        // Smaller than any step's output budget alone, so no Arc prompt fits.
        val engine = FakeLocalInferenceEngine(
            capacity = deviceAvailable(maxContextTokens = 64),
            respond = { Result.success("{}") },
        )
        val session = ArcSession.create(
            arcConfig = onDeviceArcConfig("bridge-window-arc"),
            projectDirPath = arcProjectDir("bridge-window").toString(),
            maxFlowTicks = 1,
            database = Database(driver),
            engine = engine,
            cloud = null,
        )

        try {
            val state = assertNotNull(session.onDeviceState)
            val handle = session.start("Add a health check endpoint")
            withTimeout(timeoutMillis) { assertIs<ArcOutcome.Completed>(handle.await()) }

            assertEquals(0, engine.generateCount, "A prompt that does not fit must not be given to the engine")
            assertTrue(engine.probeCount >= 1)

            // The device was available — it was the prompt that did not fit — so this is not a
            // fallback the relay announces; it is a step that was never eligible for that window.
            val snapshot = state.value
            val availability = assertIs<OnDeviceAvailability.Available>(snapshot.availability)
            assertEquals(64, availability.maxContextTokens)
            assertNull(snapshot.lastFallback)
            assertEquals(0, snapshot.servedOnDevice)
            assertEquals(0, snapshot.failedOnDevice)

            val invocations = handle.modelInvocations()
            assertTrue(invocations.isNotEmpty())
            invocations.forEach { invocation ->
                assertEquals(false, invocation.success)
                assertTrue(invocation.providerId != AIProvider_OnDevice.id)
            }
        } finally {
            session.close()
            driver.close()
        }
    }

    /** A session without an engine has no on-device state to show, and says so. */
    @Test
    fun `a session without an engine reports no on-device state`() = runBlocking<Unit> {
        val session = ArcSession.create(
            arcConfig = arcConfig("bridge-no-engine-arc"),
            projectDirPath = arcProjectDir("bridge-no-engine").toString(),
            maxFlowTicks = 1,
        )
        try {
            assertNull(session.onDeviceState)
            assertNull(session.observeOnDeviceState { })
            assertNull(session.refreshOnDeviceAvailability())
        } finally {
            session.close()
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
