package link.socket.ampere.domain.arc

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.db.Database
import link.socket.ampere.trace.ArcTraceProjection
import okio.Path.Companion.toPath

/**
 * AMPR-359's objective, end to end, on the one path that matters most: the caller-owned scope is
 * cancelled mid-Flow, so `execute` returns no outcome at all and the sink is the only place the
 * run's record can land. The manifest is then read back from the store alone — closed and opened
 * again, as a later process would find it.
 *
 * Real dispatchers, for the reason `AmpereRuntimeTest` gives: the cancel has to race a Flow loop
 * genuinely running on another thread.
 */
class CompletionManifestPersistenceTest {

    @Test
    fun `a caller-cancelled run's manifest is read back after its store is closed and reopened`() =
        runBlocking<Unit> {
            val dbPath = createTempDirectory("manifest-persistence").resolve("ampere.db").toString()
            val runId = "run-caller-cancelled"

            // The process that runs the Arc: a store and bus of its own, and an agent scope that
            // belongs to its caller.
            val infraScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val writer = createJvmDriver(dbPath)
            try {
                val sink = CompletionManifestSink(
                    eventApi = AgentEventApi(
                        agentId = CompletionManifestSink.DEFAULT_AGENT_ID,
                        eventRepository = EventRepository(DEFAULT_JSON, infraScope, Database(writer)),
                        eventSerialBus = EventSerialBus(infraScope),
                    ),
                )
                val runtime = AmpereRuntime(
                    arcConfig = ArcConfig(
                        name = "persist-arc",
                        agents = listOf(ArcAgentConfig(role = "code")),
                        orchestration = OrchestrationConfig(
                            type = OrchestrationType.SEQUENTIAL,
                            order = listOf("code"),
                        ),
                    ),
                    projectDir = arcProjectDir().toString().toPath(),
                    agentScope = callerScope,
                    maxFlowTicks = Int.MAX_VALUE,
                    completionManifestSink = sink::record,
                )

                val run = callerScope.async { runtime.execute("Implement a very long running goal", runId) }
                awaitFlowUnderway(runtime)

                callerScope.cancel()

                // No outcome comes back: the caller was cancelled, so `execute` rethrows. `await`
                // returns only once the run has settled, and the manifest is written before that.
                assertFailsWith<CancellationException> { run.await() }
                assertEquals(0, sink.failures, "The manifest write must not have failed")
            } finally {
                callerScope.cancel()
                infraScope.cancel()
                writer.close()
            }

            // A later process: nothing of the run survives but the file.
            val reader = createJvmDriver(dbPath)
            try {
                val completion = assertNotNull(
                    ArcTraceProjection(Database(reader)).project(runId).getOrThrow().completion,
                    "The manifest must be readable from the persisted store alone",
                )

                assertEquals(runId, completion.runId)
                assertEquals(TerminationReason.CANCELLED, completion.endedBy)
                assertNull(completion.failure)
                assertEquals(ArcPhase.FLOW, completion.endedDuring)
                assertEquals(listOf(ArcPhase.CHARGE, ArcPhase.FLOW), completion.phasesStarted)
                assertEquals(listOf(ArcPhase.PULSE), completion.phasesNotRun, "Pulse never ran, so no Knowledge")
                assertTrue((completion.reachedTick ?: 0) >= 1, "Flow was underway when the cancel landed")
                assertNotNull(completion.unmetGoals, "Charge finished, so what was left undone is known")
                assertTrue(
                    completion.producedOutcomes.single().total >= 1,
                    "The agent ticked, so it produced outcomes",
                )
            } finally {
                reader.close()
            }
        }

    /** Wait until Flow has completed a tick, so the cancel after this is genuinely mid-Flow. */
    private suspend fun awaitFlowUnderway(runtime: AmpereRuntime) {
        withTimeout(60_000) {
            while ((runtime.flowPhase?.getCurrentTick() ?: 0) < 1) {
                delay(5)
            }
        }
    }

    /** A temp dir with the AGENTS.md/README.md that ChargePhase requires to produce a context. */
    private fun arcProjectDir(): java.nio.file.Path {
        val tempDir = createTempDirectory("manifest-persistence-project")
        tempDir.resolve("README.md").writeText("# PersistProject\n\nA test project for manifest persistence.")
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
}
