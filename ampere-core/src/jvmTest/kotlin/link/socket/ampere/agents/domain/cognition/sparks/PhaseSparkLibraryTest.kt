package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PhaseSparkLibraryTest {

    @Test
    fun `all returns sparks for every bundled fixture`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()
        val sparks = library.all().filterIsInstance<DeclarativePhaseSpark>()

        val ids = sparks.map { it.sparkId }.toSet()
        assertEquals(
            setOf("cooking-domain", "recipe-arc-task", "minimal-edge"),
            ids,
        )
    }

    @Test
    fun `byId returns a matching spark and null for unknown ids`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        val cooking = library.byId("cooking-domain")
        assertNotNull(cooking)
        assertTrue(cooking is DeclarativePhaseSpark)
        assertEquals("PhaseSpark:cooking-domain", cooking.name)

        assertNull(library.byId("does-not-exist"))
    }

    @Test
    fun `selectFor returns cooking spark for PLAN + recipe text`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        val results = library.selectFor(
            SparkSelectionContext(
                phase = CognitivePhase.PLAN,
                text = "Please help me draft a recipe for risotto",
            ),
        )

        val ids = results.filterIsInstance<DeclarativePhaseSpark>().map { it.sparkId }
        assertTrue("cooking-domain" in ids, "expected cooking-domain in $ids")
        assertTrue("recipe-arc-task" in ids, "expected recipe-arc-task in $ids")
    }

    @Test
    fun `selectFor returns minimal-edge for PLAN with no keyword match`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        val results = library.selectFor(
            SparkSelectionContext(
                phase = CognitivePhase.PLAN,
                text = "",
            ),
        )

        val ids = results.filterIsInstance<DeclarativePhaseSpark>().map { it.sparkId }
        assertTrue(
            "minimal-edge" in ids,
            "minimal-edge should match PLAN with no keyword filter, got $ids",
        )
    }

    @Test
    fun `selectFor results are stably ordered by spark id`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        val results = library.selectFor(
            SparkSelectionContext(
                phase = CognitivePhase.PLAN,
                text = "",
            ),
        )

        val ids = results.filterIsInstance<DeclarativePhaseSpark>().map { it.sparkId }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `parse failures are skipped, not crashed on`() = runTest {
        val library = DefaultPhaseSparkLibrary.load(
            sparkResourcePaths = listOf(
                "files/sparks/minimal-edge.spark.md",
                "files/sparks/does-not-exist.spark.md",
            ),
        )

        val ids = library.all().filterIsInstance<DeclarativePhaseSpark>().map { it.sparkId }.toSet()
        assertEquals(setOf("minimal-edge"), ids)
    }

    @Test
    fun `EXECUTE phase only returns sparks that declared EXECUTE`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        val results = library.selectFor(
            SparkSelectionContext(
                phase = CognitivePhase.EXECUTE,
                text = "recipe",
            ),
        )

        val ids = results.filterIsInstance<DeclarativePhaseSpark>().map { it.sparkId }.toSet()
        // cooking-domain declares PLAN, EXECUTE; recipe-arc-task is PLAN only.
        assertTrue("cooking-domain" in ids, "expected cooking-domain in $ids")
        assertTrue("recipe-arc-task" !in ids, "recipe-arc-task should not match EXECUTE")
    }
}
