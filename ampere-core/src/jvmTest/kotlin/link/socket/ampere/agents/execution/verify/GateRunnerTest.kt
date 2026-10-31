package link.socket.ampere.agents.execution.verify

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * The gate runner against real processes (AMPR-309, tasks 2 and 3): `sh` scripts stand
 * in for the build tools, and a fake `gradlew` records how a `fresh` gate was invoked.
 */
class GateRunnerTest {

    private val posix = File.separatorChar == '/'
    private val temporary = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporary.forEach { it.toFile().deleteRecursively() }
    }

    private fun root(): Path = Files.createTempDirectory("ampere-gates").toRealPath().also { temporary.add(it) }

    /** Preconditions by id: present unless listed, and each answer can change per call. */
    private class FakePreconditions(var absent: (String, Int) -> Boolean = { _, _ -> false }) : PreconditionChecker {
        val calls = mutableMapOf<String, Int>()

        override suspend fun check(precondition: Precondition, root: Path): PreconditionCheck {
            val n = calls.merge(precondition.id, 1, Int::plus)!!
            return if (absent(precondition.id, n)) {
                PreconditionCheck.Absent("${precondition.id} is not on this host")
            } else {
                PreconditionCheck.Satisfied
            }
        }
    }

    private fun runner(
        root: Path,
        preconditions: PreconditionChecker = FakePreconditions(),
        extra: Map<String, String> = emptyMap(),
        reproduce: Boolean = true,
    ) = GateRunner(
        preconditions = preconditions,
        environment = System.getenv() + mapOf("PROBE" to root.toString()) + extra,
        reproduceFailures = reproduce,
        grace = 500.milliseconds,
        pollInterval = 20.milliseconds,
    )

    private fun gate(
        id: String,
        run: String,
        fresh: Boolean = false,
        timeout: Duration = 1.minutes,
        retrySafe: Boolean = true,
        requires: List<String> = emptyList(),
        needs: List<String> = emptyList(),
        steps: List<GateStep>? = null,
    ) = ManifestGate(
        id = id,
        steps = steps ?: listOf(GateStep(id = null, run = run)),
        sequenced = steps != null,
        fresh = fresh,
        timeout = timeout,
        retrySafe = retrySafe,
        sideEffects = SideEffects.BUILD_DIR,
        parallelSafe = false,
        requires = requires,
        needs = needs,
        baseline = Baseline.CI,
    )

    private fun manifest(vararg gates: ManifestGate) = VerifyManifest(
        version = 1,
        requires = listOf(Precondition("jdk-21", PreconditionPredicate.Toolchain("21"))),
        optional = listOf(Precondition("simulator", PreconditionPredicate.Simulator("iPhone *"))),
        gates = gates.toList(),
    )

    private fun sh(script: String) = "sh -c '$script'"

    private fun run(
        runner: GateRunner,
        manifest: VerifyManifest,
        root: Path,
        listener: GateListener = GateListener.NONE,
        stopping: () -> Boolean = { false },
    ): VerificationReport = runBlocking {
        withTimeout(60.seconds) { runner.run(manifest, root, root.resolve("logs"), listener, stopping) }
    }

    @Test
    fun `every gate runs in order whatever the one before it did, and a reproducible failure is the agent's`() {
        if (!posix) return
        val root = root()
        val manifest = manifest(
            gate("first", sh("echo first >> \"\$PROBE/order\"")),
            gate(
                "broken",
                sh("echo broken >> \"\$PROBE/order\"; echo \"> Task :app:test FAILED\"; exit 3"),
            ),
            gate("last", sh("echo last >> \"\$PROBE/order\"")),
        )

        val report = run(runner(root), manifest, root)

        assertEquals(listOf("first", "broken", "broken", "last"), Files.readAllLines(root.resolve("order")))
        assertEquals(
            listOf(GateOutcome.PASSED, GateOutcome.FAILED, GateOutcome.PASSED),
            report.gates.map { it.outcome },
        )
        val broken = report.gates[1]
        assertEquals(3, broken.exitCode)
        assertEquals("exited 3, and again when re-run", broken.reason)
        assertEquals(listOf("> Task :app:test FAILED"), broken.excerpt)
        assertTrue(broken.tail.contains("> Task :app:test FAILED"))
        assertTrue(Files.readString(root.resolve("logs/broken.log")).contains("re-run"))
        assertEquals(VerificationVerdict.AGENT_FAILURE, report.verdict)
    }

    @Test
    fun `a failure that does not reproduce is the host's and spends nothing`() {
        if (!posix) return
        val root = root()
        val flaky = gate("flaky", sh("if [ -f \"\$PROBE/seen\" ]; then exit 0; fi; touch \"\$PROBE/seen\"; exit 1"))

        val report = run(runner(root), manifest(flaky), root)

        assertEquals(GateOutcome.INFRASTRUCTURE, report.gates.single().outcome)
        assertTrue("passed when re-run" in report.gates.single().reason)
        assertEquals(VerificationVerdict.HOST_FAILURE, report.verdict)
    }

    @Test
    fun `a gate that is not retry-safe is never re-run`() {
        if (!posix) return
        val root = root()
        val once = gate("once", sh("echo ran >> \"\$PROBE/runs\"; exit 1"), retrySafe = false)

        val report = run(runner(root), manifest(once), root)

        assertEquals(1, Files.readAllLines(root.resolve("runs")).size)
        assertEquals(GateOutcome.FAILED, report.gates.single().outcome)
    }

    @Test
    fun `a gate past its timeout is terminated with its whole group and reported as infrastructure`() {
        if (!posix) return
        val root = root()
        val addresses = CopyOnWriteArrayList<CancellationAddress>()
        val listener = object : GateListener {
            override suspend fun onGateStarted(gate: ManifestGate, step: GateStep, address: CancellationAddress) {
                addresses += address
            }
        }

        val report = run(
            runner(root),
            manifest(gate("hangs", sh("sleep 30 & sleep 30"), timeout = 500.milliseconds)),
            root,
            listener,
        )

        assertEquals(GateOutcome.INFRASTRUCTURE, report.gates.single().outcome)
        assertTrue("timeout" in report.gates.single().reason)
        assertFalse(ProcessGroups.isAlive(addresses.single()), "the timed-out gate's group must be gone")
    }

    @Test
    fun `an unset env reference never starts the gate`() {
        if (!posix) return
        val root = root()
        val gate = gate("needs-var", "touch \${env.AMPERE_TEST_UNSET_VARIABLE}")

        val report = run(runner(root), manifest(gate), root)

        assertEquals(GateOutcome.INFRASTRUCTURE, report.gates.single().outcome)
        assertTrue("AMPERE_TEST_UNSET_VARIABLE" in report.gates.single().reason)
    }

    @Test
    fun `a missing executable is the host's`() {
        if (!posix) return
        val root = root()
        val report = run(runner(root), manifest(gate("missing", "ampere-no-such-tool-anywhere --version")), root)

        assertEquals(GateOutcome.INFRASTRUCTURE, report.gates.single().outcome)
    }

    @Test
    fun `an absent hard precondition refuses the run and nothing executes`() {
        if (!posix) return
        val root = root()
        val preconditions = FakePreconditions { id, _ -> id == "jdk-21" }
        val manifest = manifest(gate("a", sh("touch \"\$PROBE/ran\"")), gate("b", sh("touch \"\$PROBE/ran\"")))

        val report = run(runner(root, preconditions), manifest, root)

        assertEquals(VerificationVerdict.REFUSED, report.verdict)
        assertEquals(listOf("jdk-21"), report.refusedBy.keys.toList())
        assertTrue(report.gates.all { it.outcome == GateOutcome.NOT_EXECUTED && "`jdk-21`" in it.reason })
        assertFalse(Files.exists(root.resolve("ran")))
    }

    @Test
    fun `an absent soft precondition reports only the gates that need it, and the run is not green`() {
        if (!posix) return
        val root = root()
        val preconditions = FakePreconditions { id, _ -> id == "simulator" }
        val manifest = manifest(
            gate("unit", sh("true")),
            gate("ios", sh("touch \"\$PROBE/ios-ran\""), needs = listOf("simulator")),
        )

        val report = run(runner(root, preconditions), manifest, root)

        assertEquals(listOf(GateOutcome.PASSED, GateOutcome.NOT_EXECUTED), report.gates.map { it.outcome })
        assertTrue("`simulator`" in report.gates[1].reason)
        assertFalse(Files.exists(root.resolve("ios-ran")))
        assertEquals(VerificationVerdict.INCOMPLETE, report.verdict)
    }

    @Test
    fun `a precondition that broke while the gate ran makes its failure the host's`() {
        if (!posix) return
        val root = root()
        // Present at the pre-check, absent on the re-check after the failure.
        val preconditions = FakePreconditions { id, n -> id == "jdk-21" && n > 1 }
        val gate = gate("uses-jdk", sh("exit 1"), requires = listOf("jdk-21"))

        val report = run(runner(root, preconditions), manifest(gate), root)

        assertEquals(GateOutcome.INFRASTRUCTURE, report.gates.single().outcome)
        assertTrue("`jdk-21`" in report.gates.single().reason)
    }

    @Test
    fun `steps stop at the first failure and the result names that step`() {
        if (!posix) return
        val root = root()
        val gate = gate(
            "bridge",
            run = "",
            steps = listOf(
                GateStep("link", sh("touch \"\$PROBE/linked\"")),
                GateStep("build", sh("exit 4")),
                GateStep("test", sh("touch \"\$PROBE/tested\"")),
            ),
            retrySafe = false,
        )

        val report = run(runner(root), manifest(gate), root)

        val result = report.gates.single()
        assertEquals(GateOutcome.FAILED, result.outcome)
        assertEquals("build", result.step)
        assertEquals(4, result.exitCode)
        assertTrue(Files.exists(root.resolve("linked")))
        assertFalse(Files.exists(root.resolve("tested")))
    }

    /**
     * Task 2's validation, against a stand-in for Gradle: a stale result directory that
     * would let a cached test gate read green, and a `gradlew` that records how it was
     * called. The fresh gate must delete its own results and ask Gradle for a real
     * execution; the same command without `fresh` must be left alone.
     */
    @Test
    fun `a fresh gradle gate deletes its own stale results and runs without the build cache, its task re-run`() {
        if (!posix) return
        val root = root()
        val gradlew = root.resolve("gradlew")
        Files.writeString(
            gradlew,
            "#!/bin/sh\n" +
                "[ -d \"\$PROBE/app/build/test-results/test\" ] && echo stale >> \"\$PROBE/saw\"\n" +
                "echo \"\$*\" >> \"\$PROBE/args\"\n",
        )
        gradlew.toFile().setExecutable(true)
        val stale = Files.createDirectories(root.resolve("app/build/test-results/test"))
        Files.writeString(stale.resolve("TEST-cached.xml"), "<testsuite tests=\"0\"/>")

        val report = run(
            runner(root),
            manifest(
                gate("unit", "./gradlew :app:test", fresh = true),
                gate("lint", "./gradlew :app:lint"),
            ),
            root,
        )

        assertEquals(VerificationVerdict.GREEN, report.verdict)
        assertEquals(
            listOf("--no-build-cache :app:test --rerun", ":app:lint"),
            Files.readAllLines(root.resolve("args")),
        )
        assertFalse(Files.exists(root.resolve("saw")), "the fresh gate ran with its stale results still present")
        assertFalse(Files.exists(stale))
    }

    @Test
    fun `a shutdown stops the running gate and reports the rest as not executed`() {
        if (!posix) return
        val root = root()
        val started = AtomicInteger()
        val listener = object : GateListener {
            override suspend fun onGateStarted(gate: ManifestGate, step: GateStep, address: CancellationAddress) {
                started.incrementAndGet()
            }
        }

        val report = run(
            runner(root),
            manifest(gate("long", sh("sleep 30")), gate("after", sh("touch \"\$PROBE/after\""))),
            root,
            listener,
            stopping = { started.get() > 0 },
        )

        assertEquals(VerificationVerdict.INTERRUPTED, report.verdict)
        assertEquals(listOf(GateOutcome.INFRASTRUCTURE, GateOutcome.NOT_EXECUTED), report.gates.map { it.outcome })
        assertFalse(Files.exists(root.resolve("after")))
    }

    @Test
    fun `every gate settles through the listener, executed or not`() {
        if (!posix) return
        val root = root()
        val settled = CopyOnWriteArrayList<String>()
        val listener = object : GateListener {
            override suspend fun onGateSettled(result: GateResult) {
                settled += result.gateId
            }
        }
        val preconditions = FakePreconditions { id, _ -> id == "simulator" }

        run(
            runner(root, preconditions),
            manifest(gate("a", sh("true")), gate("b", sh("true"), needs = listOf("simulator"))),
            root,
            listener,
        )

        assertEquals(listOf("a", "b"), settled)
    }
}
