package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that `LanguageSpark.Kotlin` carries the Kotlin-specific guidance
 * that the language-neutral `code-agent.spark.md` deliberately omits — both
 * the always-on `promptContribution` and the per-phase contributions that
 * appear alongside `## When Planning` / `## When Executing` once the spark
 * is on the stack.
 */
class LanguageSparkKotlinTest {

    @Test
    fun `always-on contribution covers package-from-path convention`() {
        val contribution = LanguageSpark.Kotlin.promptContribution
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
    fun `always-on contribution rules out incomplete generations`() {
        val contribution = LanguageSpark.Kotlin.promptContribution
        assertTrue(
            contribution.contains("TODO", ignoreCase = false),
            "Kotlin spark should explicitly disallow TODO placeholders in generated code",
        )
    }

    @Test
    fun `per-phase contributions exist for PLAN and EXECUTE`() {
        val contributions = LanguageSpark.Kotlin.phaseContributions
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
}
