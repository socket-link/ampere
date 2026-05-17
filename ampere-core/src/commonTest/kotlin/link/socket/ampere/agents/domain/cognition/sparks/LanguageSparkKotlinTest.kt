package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies that the declarative Kotlin language spark carries the
 * Kotlin-specific guidance that the language-neutral `code-agent.spark.md`
 * deliberately omits.
 */
class LanguageSparkKotlinTest {

    @Test
    fun `always-on contribution covers package-from-path convention`() = runTest {
        val contribution = loadKotlinSpark().promptContribution
        assertTrue(
            contribution.contains("src/commonMain/kotlin"),
            "Kotlin spark should cover the multiplatform source-root convention",
        )
        assertTrue(
            contribution.contains("package link.socket.ampere"),
            "Kotlin spark should illustrate the package-from-path convention with a concrete example",
        )
    }

    @Test
    fun `always-on contribution rules out incomplete generations`() = runTest {
        val contribution = loadKotlinSpark().promptContribution
        assertTrue(
            contribution.contains("TODO", ignoreCase = false),
            "Kotlin spark should explicitly disallow TODO placeholders in generated code",
        )
    }

    @Test
    fun `per-phase contributions exist for PLAN and EXECUTE`() = runTest {
        val contributions = loadKotlinSpark().phaseContributions
        assertEquals(
            setOf(CognitivePhase.PLAN, CognitivePhase.EXECUTE),
            contributions.keys,
            "Kotlin spark should provide guidance during PLAN and EXECUTE phases",
        )

        val plan = assertNotNull(contributions[CognitivePhase.PLAN])
        assertTrue(plan.contains(".kt"), "PLAN guidance should mention the .kt file extension")

        val execute = assertNotNull(contributions[CognitivePhase.EXECUTE])
        assertTrue(
            execute.contains("expect") && execute.contains("actual"),
            "EXECUTE guidance should call out expect/actual mismatches as a critical failure mode",
        )
    }

    @Test
    fun `file access scope matches Kotlin source patterns`() = runTest {
        val scope = assertNotNull(loadKotlinSpark().fileAccessScope)

        assertEquals(setOf("**/*.kt", "**/*.kts", "**/*.xml", "**/*.gradle*"), scope.readPatterns)
        assertEquals(setOf("**/*.kt", "**/*.kts"), scope.writePatterns)
        assertEquals(emptySet(), scope.forbiddenPatterns)
    }

    private suspend fun loadKotlinSpark(): LanguageSpark {
        val library = DefaultPhaseSparkLibrary.load()
        val spark = library.languageSparkById(LanguageSparkIds.KOTLIN)
            ?: error("language-kotlin.spark.md is missing from production resources")
        return assertIs<LanguageSpark>(spark)
    }
}
