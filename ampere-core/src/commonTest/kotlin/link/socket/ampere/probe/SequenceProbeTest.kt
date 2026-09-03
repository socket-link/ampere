package link.socket.ampere.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

/** AMPR-322 task 4 validation: referential integrity and acyclicity over a [CanonWorkGraph]. */
class SequenceProbeTest {

    private val probe = SequenceProbe()

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("link-1"),
            sourceSystem = "linear",
            nativeId = "native-1",
            etag = null,
        ),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        nativePayload = NativePayload(schema = NativeSchema("Issue"), fields = JsonObject(emptyMap())),
    )

    private val project = CanonProject(
        CanonId("pj"),
        provenance,
        name = "Blueprint",
        status = CanonWorkStatus.IN_PROGRESS,
    )

    private fun item(id: String, vararg dependsOn: String) = CanonWorkItem(
        CanonId(id),
        provenance,
        title = id,
        status = CanonWorkStatus.TODO,
        projectId = project.canonId,
        dependsOn = dependsOn.map(::CanonId),
    )

    private fun graph(vararg items: CanonWorkItem) = CanonWorkGraph(project = project, items = items.toList())

    @Test
    fun `an empty graph holds`() = runTest {
        assertEquals(Verdict.Holds(), probe.evaluate(graph()))
    }

    @Test
    fun `a DAG holds`() = runTest {
        val subject = graph(
            item("d", "b", "c"),
            item("c", "a"),
            item("b", "a"),
            item("a"),
        )

        assertEquals(Verdict.Holds(), probe.evaluate(subject))
    }

    @Test
    fun `a dangling edge is violated naming both ids`() = runTest {
        val subject = graph(item("a"), item("b", "a", "ghost"))

        assertEquals(Verdict.Violated(reason = "dangling dependsOn: b -> ghost"), probe.evaluate(subject))
    }

    @Test
    fun `a three-cycle is violated naming the path`() = runTest {
        val subject = graph(item("a", "b"), item("b", "c"), item("c", "a"))

        assertEquals(Verdict.Violated(reason = "cycle: a -> b -> c -> a"), probe.evaluate(subject))
    }

    @Test
    fun `a cycle reached through an acyclic prefix reports only the loop`() = runTest {
        val subject = graph(item("start", "x"), item("x", "y"), item("y", "x"))

        assertEquals(Verdict.Violated(reason = "cycle: x -> y -> x"), probe.evaluate(subject))
    }

    @Test
    fun `a self edge is a one-node cycle`() = runTest {
        assertEquals(Verdict.Violated(reason = "cycle: a -> a"), probe.evaluate(graph(item("a", "a"))))
    }

    @Test
    fun `a dangling edge is reported before a cycle`() = runTest {
        // Both checks are decidable; referential integrity wins because a cycle
        // through a node that does not exist is not yet a well-formed question.
        val subject = graph(item("a", "b"), item("b", "a", "ghost"))

        assertEquals(Verdict.Violated(reason = "dangling dependsOn: b -> ghost"), probe.evaluate(subject))
    }

    @Test
    fun `a diamond is not a cycle`() = runTest {
        val subject = graph(
            item("top", "left", "right"),
            item("left", "bottom"),
            item("right", "bottom"),
            item("bottom"),
        )

        assertEquals(Verdict.Holds(), probe.evaluate(subject))
    }

    @Test
    fun `a long chain does not overflow the stack`() = runTest {
        val chain = (0 until 20_000).map { i -> if (i == 0) item("n0") else item("n$i", "n${i - 1}") }
        val subject = CanonWorkGraph(project = project, items = chain.reversed())

        assertEquals(Verdict.Holds(), probe.evaluate(subject))
    }

    @Test
    fun `the probe registers under its default id`() {
        val registry = ProbeRegistry().registerAmpereProbes()

        val registered = registry.get(ProbeId("ampere.sequence"))
        assertNotNull(registered)
        assertIs<SequenceProbe>(registered)
        assertEquals(ProbeId(SequenceProbe.ID), registered.id)
    }
}
