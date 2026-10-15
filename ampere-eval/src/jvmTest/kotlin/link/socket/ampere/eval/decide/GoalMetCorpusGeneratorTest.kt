package link.socket.ampere.eval.decide

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The corpus generator as a command (AMPR-422 task 2).
 *
 * ```
 * ./gradlew :ampere-eval:generateGoalMetCorpus
 * git diff --stat ampere-eval/src/jvmTest/resources/decide
 * ```
 *
 * Does nothing unless [GoalMetCorpus.DIR_PROPERTY] is set, which only that task does; an
 * ordinary `jvmTest` run must not rewrite the corpus the validator is checking against. An
 * empty diff after a run is the proof the generator is deterministic; a non-empty one means a
 * rule or a text changed, and every recorded answer keyed on a changed state is stale.
 */
class GoalMetCorpusGeneratorTest {

    @Test
    fun `writes the corpus when asked`() {
        val directory = GoalMetCorpus.outputDirectory()
        if (directory == null) {
            println(
                "[goal-met-corpus] skipped: -D${GoalMetCorpus.DIR_PROPERTY} is not set. " +
                    "Run ./gradlew :ampere-eval:generateGoalMetCorpus to regenerate.",
            )
            return
        }
        val cases = GoalMetCorpus.generate()
        GoalMetCorpus.write(directory, cases)
        assertEquals(cases, GoalMetCorpus.decode(GoalMetCorpus.encode(cases)))
        println("[goal-met-corpus] wrote ${cases.size} case(s) to ${directory.resolve("cases.json")}")
    }
}
