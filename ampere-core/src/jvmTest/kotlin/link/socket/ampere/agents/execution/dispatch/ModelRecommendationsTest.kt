package link.socket.ampere.agents.execution.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals

/** The model/effort recommendation line (AMPR-306, from the AMPR-288 ethnography). */
class ModelRecommendationsTest {

    private val default = ModelChoice(model = "sonnet", effort = "medium")

    @Test
    fun `this ticket's own line asks for Opus at high effort, not the parenthetical Fable`() {
        val choice = ModelRecommendations.resolve(
            "**Model/effort recommendation:** Opus (or stronger, e.g. Fable), high — this is the supervisor's " +
                "core loop.\n\n## Context",
            default,
        )
        assertEquals(ModelChoice("opus", "high", setOf(ModelChoice.Half.MODEL, ModelChoice.Half.EFFORT)), choice)
    }

    @Test
    fun `the conventions' example reads as Sonnet at low effort`() {
        val choice = ModelRecommendations.resolve(
            "**Model/effort recommendation:** Sonnet, low — documentation and templates; " +
                "all decisions are already ratified.",
            default,
        )
        assertEquals("sonnet", choice.model)
        assertEquals("low", choice.effort)
    }

    @Test
    fun `a ticket with no line gets the configured default`() {
        assertEquals(default, ModelRecommendations.resolve("## Context\n\nNo recommendation here.", default))
        assertEquals(default, ModelRecommendations.resolve(null, default))
    }

    @Test
    fun `a line anywhere but first is prose, not a recommendation`() {
        val choice = ModelRecommendations.resolve(
            "## Context\n\n**Model/effort recommendation:** Haiku, low — buried.",
            default,
        )
        assertEquals(default, choice)
    }

    @Test
    fun `each half falls back on its own`() {
        val choice = ModelRecommendations.resolve("**Model/effort recommendation:** Haiku — a small fix.", default)
        assertEquals(ModelChoice("haiku", "medium", setOf(ModelChoice.Half.MODEL)), choice)
    }
}
