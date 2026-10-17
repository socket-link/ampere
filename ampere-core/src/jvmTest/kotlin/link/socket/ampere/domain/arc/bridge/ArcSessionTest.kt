package link.socket.ampere.domain.arc.bridge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
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
import link.socket.ampere.agents.domain.routing.local.OnDeviceAvailability
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
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
import link.socket.ampere.llm.UpstreamLlmClient
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
     * An Arc whose one step declares [floor], or none. `ONE` is the lowest floor a step may
     * declare: a `ZERO` floor is refused where the Arc is written (AMPR-459), so no Arc step is
     * eligible for the device and the bridge's engine serves none of them.
     */
    private fun flooredArcConfig(name: String, floor: CapabilityRung?) = ArcConfig(
        name = name,
        agents = listOf(ArcAgentConfig(role = "code", minimumRung = floor)),
        orchestration = OrchestrationConfig(
            type = OrchestrationType.SEQUENTIAL,
            order = listOf("code"),
        ),
    )

    /** The floors an Arc step may declare: none, and the lowest legal one. */
    private val legalFloors: List<CapabilityRung?> = listOf(null, CapabilityRung.ONE)

    /** The model a step with no floor is configured with: the default configuration's. */
    private val configuredModel: String = AIConfigurationFactory.getDefaultConfiguration().model.name

    private fun deviceAvailable(maxContextTokens: Int) = LocalCapacity(
        available = true,
        modelId = AIModel_OnDevice.AppleFoundationModels.name,
        maxContextTokens = maxContextTokens,
        providerId = AIProvider_OnDevice.id,
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

    // ---- on-device (AMPR-374, AMPR-459) -----------------------------------------------

    /**
     * An engine-bound session whose device could take every prompt, and still no Arc step hands
     * it one (AMPR-459): a step runs the PROPEL loop, and rung ZERO serves display text only. With
     * no floor and with `ONE`, every call goes to the cloud transport — with no floor to the
     * agent's own configured model, as before AMPR-459; with `ONE` cheapest-capable over the rated
     * cloud models. The binding still probes the engine, so the session's state shows a device
     * that is there, available and unused.
     */
    @Test
    fun `an engine-bound session's Arc steps never hand the engine a prompt`() = runBlocking<Unit> {
        legalFloors.forEach { floor ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
            // A window far larger than the catalog's 4,096 (the live model's figure, AMPR-373),
            // adopted by the binding, so it is not a prompt's size that keeps it off the device.
            val engine = FakeLocalInferenceEngine(
                capacity = deviceAvailable(maxContextTokens = 32_768),
                respond = { Result.success("{}") },
            )
            val cloud = CloudTransport()
            val session = ArcSession.create(
                arcConfig = flooredArcConfig("bridge-engine-bound-arc", floor),
                projectDirPath = arcProjectDir("bridge-engine-bound").toString(),
                maxFlowTicks = 1,
                database = Database(driver),
                engine = engine,
                cloud = cloud,
            )

            try {
                val state = assertNotNull(session.onDeviceState, "An engine-bound session exposes its state")
                val observed = Channel<OnDeviceInferenceState>(Channel.UNLIMITED)
                val observation = assertNotNull(session.observeOnDeviceState { observed.trySend(it) })

                val handle = session.start("Add a health check endpoint")
                withTimeout(timeoutMillis) { assertIs<ArcOutcome.Completed>(handle.await()) }

                assertEquals(0, engine.generateCount, "floor $floor: no Arc step may hand the engine a prompt")
                val cloudCalls = cloud.calls.get()
                assertTrue(cloudCalls >= 1, "floor $floor: the steps ran, and in the cloud")

                val settled = withTimeout(timeoutMillis) {
                    state.first { it.servedInCloud >= cloudCalls && !it.isInUse }
                }
                assertEquals(cloudCalls, settled.servedInCloud)
                assertEquals(0, settled.servedOnDevice)
                assertEquals(0, settled.failedOnDevice)
                // The device was never a candidate, so the relay never routed around it either.
                assertNull(settled.lastFallback)
                val availability = assertIs<OnDeviceAvailability.Available>(settled.availability)
                assertEquals(32_768, availability.maxContextTokens, "The engine's own window is what the state shows")

                // The Swift-facing observation saw the same facts.
                val lastObserved = withTimeout(timeoutMillis) {
                    var latest = observed.receive()
                    while (latest.servedInCloud < cloudCalls) latest = observed.receive()
                    latest
                }
                assertEquals(0, lastObserved.servedOnDevice)
                observation.cancel()

                // Persisted under the run id: every step ran in the cloud, and a step with no floor
                // ran on the model it was configured with.
                val invocations = handle.modelInvocations()
                assertEquals(cloudCalls, invocations.size)
                invocations.forEach { invocation ->
                    assertTrue(invocation.providerId != AIProvider_OnDevice.id)
                    assertEquals(true, invocation.success)
                    if (floor == null) {
                        assertEquals(configuredModel, invocation.modelId)
                    } else {
                        assertTrue(
                            invocation.routingReason.orEmpty().startsWith("capability:"),
                            "A ONE floor routes cheapest-capable; got ${invocation.routingReason}",
                        )
                    }
                }
            } finally {
                session.close()
                driver.close()
            }
        }
    }

    /**
     * A device-only session with its device available: nothing a step may run on is bound. No Arc
     * step is eligible for the device (AMPR-459), and with no cloud transport every model call
     * fails before anything is sent — each on record as a failure to find a transport, never under
     * the on-device provider, and none of them retried on the device. The steps fail; the run does
     * not, the same as a run with no transport at all.
     */
    @Test
    fun `a device-only session sends nothing and every model call fails`() = runBlocking<Unit> {
        legalFloors.forEach { floor ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
            val engine = FakeLocalInferenceEngine(
                capacity = deviceAvailable(maxContextTokens = 32_768),
                respond = { Result.success("{}") },
            )
            val session = ArcSession.create(
                arcConfig = flooredArcConfig("bridge-device-only-arc", floor),
                projectDirPath = arcProjectDir("bridge-device-only").toString(),
                maxFlowTicks = 1,
                database = Database(driver),
                engine = engine,
                cloud = null,
            )

            try {
                val state = assertNotNull(session.onDeviceState)
                val handle = session.start("Add a health check endpoint")
                withTimeout(timeoutMillis) { assertIs<ArcOutcome.Completed>(handle.await()) }

                assertEquals(0, engine.generateCount, "floor $floor: the engine must not be given a prompt")
                assertTrue(engine.probeCount >= 1, "Availability is still asked before each call, by the probe")

                val invocations = handle.modelInvocations()
                assertTrue(invocations.isNotEmpty(), "The attempts are traced, so a reader sees the steps refused")
                invocations.forEach { invocation ->
                    assertEquals(false, invocation.success)
                    assertEquals(MissingUpstreamLlmClientException::class.simpleName, invocation.errorType)
                    assertTrue(invocation.providerId != AIProvider_OnDevice.id)
                }

                val snapshot = state.value
                assertEquals(0, snapshot.servedOnDevice)
                assertEquals(0, snapshot.failedOnDevice)
                assertEquals(0, snapshot.servedInCloud)
                assertNull(snapshot.lastFallback, "The device was never a candidate, so nothing routed around it")
            } finally {
                session.close()
                driver.close()
            }
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

    /** The cloud side of an engine-bound session: answers every call with `{}`, and counts them. */
    private class CloudTransport : UpstreamLlmClient {
        val calls = AtomicInteger(0)

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            calls.incrementAndGet()
            return ChatCompletion(
                id = "cloud-completion",
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(index = 0, message = ChatMessage(role = ChatRole.Assistant, content = "{}")),
                ),
            )
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
