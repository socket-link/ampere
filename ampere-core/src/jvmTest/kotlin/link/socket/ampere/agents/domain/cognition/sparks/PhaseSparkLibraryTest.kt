package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PhaseSparkLibraryTest {

    @Test
    fun `all returns phase sparks for every bundled phase fixture`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()
        val sparks = library.all().filterIsInstance<DeclarativePhaseSpark>()

        // Role fixtures load via roleSparkById, not via all() — only phase ids appear here.
        val ids = sparks.map { it.sparkId }.toSet()
        assertEquals(
            setOf(
                "cooking-domain",
                "recipe-arc-task",
                "minimal-edge",
                "code-agent",
                "product-agent",
                "project-agent",
                "quality-agent",
            ),
            ids,
        )
    }

    @Test
    fun `roleSparkById resolves bundled role fixtures`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        RoleSparkFixtureExpectations.all.forEach { expected ->
            val role = library.roleSparkById(expected.id)
            assertNotNull(role, "role-${expected.id}.spark.md should load via the default library")
            val declarative = assertIs<DeclarativeRoleSpark>(role)

            assertEquals(expected.name, declarative.name)
            assertEquals(expected.agentRole, declarative.agentRole)
            assertEquals(expected.allowedTools, declarative.allowedTools)
            assertEquals(expected.requestedToolIds, declarative.requestedToolIds)
            assertEquals(expected.fileAccessScope, declarative.fileAccessScope)
        }
    }

    @Test
    fun `roleSparkById returns null for unknown id`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()
        assertNull(library.roleSparkById("does-not-exist"))
    }

    @Test
    fun `languageSparkById resolves bundled language fixtures`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()
        val expected = mapOf(
            LanguageSparkIds.KOTLIN to "Language:Kotlin",
            LanguageSparkIds.JAVA to "Language:Java",
            LanguageSparkIds.TYPESCRIPT to "Language:TypeScript",
            LanguageSparkIds.PYTHON to "Language:Python",
        )

        expected.forEach { (id, name) ->
            val language = library.languageSparkById(id)
            assertNotNull(language, "language-$id.spark.md should load via the default library")
            val declarative = assertIs<LanguageSpark>(language)
            assertEquals(id, declarative.languageId)
            assertEquals(name, declarative.name)
            assertNotNull(declarative.fileAccessScope)
        }
    }

    @Test
    fun `languageSparkById returns null for unknown id`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()
        assertNull(library.languageSparkById("does-not-exist"))
    }

    @Test
    fun `projectSparkById resolves bundled ampere fixture`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        val project = library.projectSparkById(ProjectSparkIds.AMPERE)
        assertNotNull(project, "project-ampere.spark.md should load via the default library")
        assertEquals("ampere", project.projectId)
        assertEquals("Project:ampere", project.name)
        assertTrue(project.projectDescription.contains("Kotlin Multiplatform"))
        assertTrue(project.conventions.contains("Package Structure"))
    }

    @Test
    fun `projectSparkById returns null for unknown id`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()
        assertNull(library.projectSparkById("does-not-exist"))
    }

    @Test
    fun `byId never returns a capability spark`() = runTest {
        // Role/language and phase sparks share the catalog but live in distinct
        // lookup surfaces. `byId` is the phase-only door; routing capability ids
        // through it would let phase-driven code paths accidentally narrow access.
        val library = DefaultPhaseSparkLibrary.load()
        assertNull(library.byId("code"))
        assertNull(library.byId("kotlin"))
        assertNull(library.byId("ampere"))
    }

    @Test
    fun `code-agent spark parses and exposes per-phase contributions`() = runTest {
        val library = DefaultPhaseSparkLibrary.load()

        val codeAgent = library.byId("code-agent")
        assertNotNull(codeAgent, "code-agent.spark.md should load via the default library")
        assertTrue(codeAgent is DeclarativePhaseSpark)

        assertEquals(
            setOf(
                CognitivePhase.PERCEIVE,
                CognitivePhase.PLAN,
                CognitivePhase.EXECUTE,
                CognitivePhase.LEARN,
            ),
            codeAgent.eligiblePhases.toSet(),
        )
        // Planning section delegates the JSON shape to the plan_steps tool.
        val planContribution = codeAgent.phaseContributions[CognitivePhase.PLAN]
        assertNotNull(planContribution)
        assertTrue(
            planContribution.contains("plan_steps"),
            "PLAN section should hand the JSON shape off to the plan_steps tool",
        )
        assertTrue(
            !planContribution.contains("estimatedComplexity"),
            "PLAN section should no longer inline the JSON schema fields",
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
