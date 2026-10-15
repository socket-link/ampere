package link.socket.ampere.eval.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.eval.decide.GoalMetCorpus.Kind
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.propel.RunQuestions

/**
 * AMPR-422 task 2 validation: the committed corpus obeys its own construction rules.
 *
 * The rules are checked on the *committed file*, not on a fresh generation, so a hand edit to
 * `cases.json` that breaks a label fails here. The last test is the one that ties the two
 * together: regenerating reproduces the committed bytes exactly.
 */
class GoalMetCorpusValidatorTest {

    private val cases = GoalMetCorpus.load()
    private val byId = cases.associateBy { it.id }

    /** A rendered step, as the state carries it. */
    private data class Step(val description: String, val status: String, val result: String)

    private data class State(val goal: String, val cycle: Int, val steps: List<Step>)

    private fun DecisionCase.parsed(): State {
        val root = Json.parseToJsonElement(state).jsonObject
        return State(
            goal = root.getValue("goal").jsonPrimitive.content,
            cycle = root.getValue("cycle").jsonPrimitive.int,
            steps = root.getValue("results").jsonArray.map { element ->
                val step = element.jsonObject
                Step(
                    description = step.getValue("step").jsonPrimitive.content,
                    status = step.getValue("status").jsonPrimitive.content,
                    result = step.getValue("result").jsonPrimitive.content,
                )
            },
        )
    }

    private fun DecisionCase.kind(): Kind = Kind.of(kind)

    /** The `clean` case of the same family and target, which every rule is defined against. */
    private fun DecisionCase.clean(): DecisionCase = byId.getValue(id.replace("/$kind/", "/${Kind.CLEAN.wireName}/"))

    @Test
    fun `the corpus is at least 200 cases and exactly half met`() {
        assertTrue(cases.size >= 200, "corpus has ${cases.size} cases")
        assertEquals(cases.size / 2, cases.count { it.kind().met }, "half met")
        assertEquals(cases.size, cases.map { it.id }.toSet().size, "ids are unique")
    }

    @Test
    fun `every kind appears at least twelve times and every family is present`() {
        val byKind = cases.groupingBy { it.kind }.eachCount()
        assertEquals(Kind.entries.map { it.wireName }.toSet(), byKind.keys)
        byKind.forEach { (kind, count) -> assertTrue(count >= 12, "kind $kind has $count cases") }
        assertEquals(GoalMetCorpus.families.map { it.id }.toSet(), cases.mapNotNull { it.family }.toSet())
        GoalMetCorpus.families.forEach { family ->
            Kind.entries.forEach { kind ->
                val present = cases.any { it.family == family.id && it.kind == kind.wireName }
                assertTrue(present, "${family.id} has no $kind")
            }
        }
    }

    @Test
    fun `no two states are equal`() {
        val duplicates = cases.groupBy { it.state }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "duplicate states: ${duplicates.values.map { group -> group.map { it.id } }}")
    }

    @Test
    fun `every case asks the shipped question at the run's clip and is labelled by its kind`() {
        cases.forEach { case ->
            assertEquals(RunQuestions.goalMet, case.question, case.id)
            val label = if (case.kind().met) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY
            assertEquals(label, case.expected, case.id)
            case.parsed().steps.forEach { step ->
                assertTrue(step.result.length <= GoalMetCorpus.CLIP + 1, "${case.id}: a result exceeds the clip")
                assertTrue("…" !in step.result, "${case.id}: a result was clipped; shorten the text instead")
            }
        }
    }

    @Test
    fun `labels follow from the construction rules`() {
        cases.forEach { case ->
            val state = case.parsed()
            val clean = case.clean().parsed()
            val id = case.id
            when (case.kind()) {
                Kind.CLEAN -> {
                    assertTrue(state.steps.size >= 2, "$id: at least two steps")
                    assertTrue(state.steps.all { it.status == "succeeded" && it.result.isNotBlank() }, "$id: succeeded")
                }
                Kind.REDUNDANT_FAILURE -> {
                    assertEquals(2, state.cycle, "$id: cycle 2")
                    val failed = state.steps.filter { it.status == "failed" }
                    assertEquals(1, failed.size, "$id: exactly one failure")
                    val retryDescription = failed.single().description + " (retry)"
                    val retry = state.steps.indexOfFirst {
                        it.status == "succeeded" && it.description == retryDescription
                    }
                    assertTrue(retry > state.steps.indexOf(failed.single()), "$id: the retry follows the failure")
                    assertEquals(
                        clean.steps.map { it.result },
                        state.steps.filter { it.status == "succeeded" }.map { it.result },
                        "$id: the successes are the clean run's",
                    )
                }
                Kind.TERSE -> {
                    assertEquals(clean.steps.map { it.description }, state.steps.map { it.description }, "$id: steps")
                    state.steps.zip(clean.steps).forEach { (terse, full) ->
                        assertEquals("succeeded", terse.status, id)
                        val shorter = terse.result.isNotBlank() && terse.result.length < full.result.length
                        assertTrue(shorter, "$id: terse is shorter")
                    }
                }
                Kind.PARTIAL -> {
                    assertEquals(clean.steps.dropLast(1), state.steps, "$id: the clean run minus its last step")
                }
                Kind.WRONG_TARGET -> {
                    assertEquals(clean.goal, state.goal, "$id: same goal")
                    assertEquals(clean.steps.map { it.description }, state.steps.map { it.description }, "$id: steps")
                    state.steps.zip(clean.steps).forEach { (wrong, right) ->
                        assertEquals("succeeded", wrong.status, id)
                        assertTrue(wrong.result != right.result, "$id: every result names the other target")
                    }
                }
                Kind.EMPTY_RESULT -> {
                    assertEquals(clean.steps.first(), state.steps.first(), "$id: the lookup is intact")
                    state.steps.drop(1).forEach { step ->
                        assertEquals("succeeded", step.status, id)
                        assertEquals("", step.result, "$id: every later result is empty")
                    }
                }
                Kind.ERROR_AS_SUCCESS -> {
                    assertEquals(clean.steps.first(), state.steps.first(), "$id: the lookup is intact")
                    state.steps.drop(1).forEach { step ->
                        assertEquals("succeeded", step.status, id)
                        val result = step.result
                        assertTrue(GoalMetCorpus.ERROR_PATTERN.containsMatchIn(result), "$id: '$result' is an error")
                    }
                }
                Kind.UNVERIFIABLE -> {
                    assertEquals(clean.steps.first(), state.steps.first(), "$id: the lookup is intact")
                    state.steps.drop(1).forEach { step ->
                        assertEquals("succeeded", step.status, id)
                        val result = step.result
                        assertTrue(GoalMetCorpus.UNVERIFIABLE_PATTERN.containsMatchIn(result), "$id: '$result' hedges")
                        assertTrue(!GoalMetCorpus.ERROR_PATTERN.containsMatchIn(result), "$id: hedging is not an error")
                    }
                }
                Kind.ALL_FAILED -> {
                    assertEquals(clean.steps.map { it.description }, state.steps.map { it.description }, "$id: steps")
                    assertTrue(state.steps.all { it.status == "failed" && it.result.isNotBlank() }, "$id: all failed")
                }
            }
        }
    }

    @Test
    fun `re-running the generator reproduces the committed file byte for byte`() {
        assertEquals(GoalMetCorpus.encode(GoalMetCorpus.generate()), GoalMetCorpus.committedText())
    }
}
