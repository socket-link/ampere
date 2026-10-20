package link.socket.ampere.eval.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.eval.decide.PerceptionDriftCorpus.Kind
import link.socket.ampere.field.FieldDelta
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.propel.RunQuestions

/**
 * AMPR-482 task 1 validation: the committed corpus obeys its own construction rules.
 *
 * The rules are checked on the *committed file*, not on a fresh generation, so a hand edit to
 * `cases.json` that breaks a label fails here. Every kind's rule is stated against the
 * `nothing_changed` sibling of the same family: the goal, the perception and the cycle's results
 * are the sibling's, and `changes` holds exactly what the kind added. The last test ties the
 * file to the generator: regenerating reproduces the committed bytes exactly.
 *
 * `./gradlew :ampere-eval:generatePerceptionDriftCorpus` runs this class with the directory
 * property set; the last test then writes the file before comparing, which is how the corpus is
 * (re)generated — never by hand.
 */
class PerceptionDriftCorpusValidatorTest {

    private val cases = PerceptionDriftCorpus.load()
    private val byId = cases.associateBy { it.id }

    private data class Belief(val kind: String, val holder: String, val about: String, val content: String)

    private data class Observation(val about: String, val content: String)

    private data class Action(val by: String, val did: String, val result: String)

    private data class Commitment(val by: String, val on: String, val due: String, val about: String?)

    private data class GoalChange(val goal: String, val from: String, val to: String, val relation: String)

    private data class Changes(
        val goals: List<GoalChange>,
        val opened: List<Commitment>,
        val resolved: List<Commitment>,
        val beliefs: List<Belief>,
        val observations: List<Observation>,
        val actions: List<Action>,
        val subjects: List<String>,
        val protectedLeftOut: Int,
    ) {
        val isEmpty: Boolean
            get() = goals.isEmpty() && opened.isEmpty() && resolved.isEmpty() && beliefs.isEmpty() &&
                observations.isEmpty() && actions.isEmpty() && subjects.isEmpty()

        /** How many of the seven lists hold anything. A kind adds to exactly one. */
        val listsTouched: Int
            get() = listOf(goals, opened, resolved, beliefs, observations, actions, subjects).count { it.isNotEmpty() }
    }

    private data class State(
        val goal: String,
        val cycle: Int,
        val perceived: String,
        val changes: Changes,
        val cycleResults: List<JsonObject>,
    )

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun DecisionCase.parsed(): State {
        val root = Json.parseToJsonElement(state).jsonObject
        val changes = root.getValue("changes").jsonObject
        val commitments = changes.getValue("commitments").jsonObject
        fun commitment(element: JsonObject) = Commitment(
            by = element.text("by"),
            on = element.text("on"),
            due = element.text("due"),
            about = element["about"]?.jsonPrimitive?.content,
        )
        return State(
            goal = root.text("goal"),
            cycle = root.getValue("cycle").jsonPrimitive.int,
            perceived = root.text("perceived"),
            changes = Changes(
                goals = changes.getValue("goals").jsonArray.map { it.jsonObject }
                    .map { GoalChange(it.text("goal"), it.text("from"), it.text("to"), it.text("relation")) },
                opened = commitments.getValue("opened").jsonArray.map { commitment(it.jsonObject) },
                resolved = commitments.getValue("resolved").jsonArray.map { commitment(it.jsonObject) },
                beliefs = changes.getValue("beliefs").jsonArray.map { it.jsonObject }
                    .map { Belief(it.text("kind"), it.text("holder"), it.text("about"), it.text("content")) },
                observations = changes.getValue("observations").jsonArray.map { it.jsonObject }
                    .map { Observation(it.text("about"), it.text("content")) },
                actions = changes.getValue("actions").jsonArray.map { it.jsonObject }
                    .map { Action(it.text("by"), it.text("did"), it.text("result")) },
                subjects = changes.getValue("subjects").jsonArray.map { it.jsonPrimitive.content },
                protectedLeftOut = changes.getValue("protected_left_out").jsonPrimitive.int,
            ),
            cycleResults = root.getValue("cycle_results").jsonArray.map { it.jsonObject },
        )
    }

    private fun DecisionCase.kind(): Kind = Kind.of(kind)

    private fun DecisionCase.familySpec(): PerceptionDriftCorpus.Family =
        PerceptionDriftCorpus.families.first { it.id == family }

    /** The `nothing_changed` case of the same family, which every rule is defined against. */
    private fun DecisionCase.nothingChanged(): DecisionCase =
        byId.getValue("$family/${Kind.NOTHING_CHANGED.wireName}")

    @Test
    fun `the corpus is 112 cases and exactly half bears`() {
        assertEquals(PerceptionDriftCorpus.families.size * Kind.entries.size, cases.size)
        assertEquals(cases.size / 2, cases.count { it.kind().bears }, "half bears")
        assertEquals(cases.size, cases.map { it.id }.toSet().size, "ids are unique")
        assertEquals(Kind.entries.size / 2, Kind.entries.count { it.bears }, "seven bears kinds and seven beside")
    }

    @Test
    fun `every kind appears once in every family`() {
        PerceptionDriftCorpus.families.forEach { family ->
            Kind.entries.forEach { kind ->
                val count = cases.count { it.family == family.id && it.kind == kind.wireName }
                assertEquals(1, count, "${family.id} has $count ${kind.wireName}")
            }
        }
        assertEquals(PerceptionDriftCorpus.families.map { it.id }.toSet(), cases.mapNotNull { it.family }.toSet())
    }

    @Test
    fun `no two states are equal except the three that production renders empty`() {
        val duplicates = cases.groupBy { it.state }.filterValues { it.size > 1 }
        assertEquals(
            PerceptionDriftCorpus.families.size,
            duplicates.size,
            "one group of equal states per family: ${duplicates.values.map { group -> group.map { it.id } }}",
        )
        duplicates.values.forEach { group ->
            assertEquals(1, group.map { it.family }.toSet().size, "a group is one family: ${group.map { it.id }}")
            assertEquals(Kind.RENDER_EMPTY, group.map { it.kind() }.toSet(), "the group is the render-empty triple")
        }
        cases.filter { it.kind() in Kind.RENDER_EMPTY }.forEach { case ->
            assertEquals(case.nothingChanged().state, case.state, "${case.id} renders as nothing_changed")
        }
    }

    @Test
    fun `every case asks the shipped question at the run's clip and is labelled by its kind`() {
        cases.forEach { case ->
            assertEquals(RunQuestions.perceptionDrift, case.question, case.id)
            val label = if (case.kind().bears) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY
            assertEquals(label, case.expected, case.id)
            assertFalse("…" in case.state, "${case.id}: a text was clipped; shorten it instead")
            val state = case.parsed()
            assertEquals(PerceptionDriftCorpus.CYCLE, state.cycle, case.id)
            assertEquals(0, state.changes.protectedLeftOut, "${case.id}: nothing protected in a constructed corpus")
            assertEquals(2, state.cycleResults.size, "${case.id}: one cycle's two results")
        }
    }

    @Test
    fun `every goal change and commitment says how it relates to a goal`() {
        cases.forEach { case ->
            val changes = case.parsed().changes
            changes.goals.forEach { change ->
                assertTrue(change.relation in RELATIONS, "${case.id}: '${change.relation}' is one of $RELATIONS")
            }
            (changes.opened + changes.resolved).forEach { commitment ->
                assertTrue(commitment.about != null, "${case.id}: every corpus commitment is on a goal")
            }
        }
    }

    @Test
    fun `the state carries no id and no timestamp`() {
        cases.forEach { case ->
            assertFalse("perception-drift-corpus" in case.state, "${case.id}: the run id")
            assertFalse("event-" in case.state, "${case.id}: a provenance")
            assertFalse("goal-" in case.state, "${case.id}: a goal's id")
            assertFalse("action-" in case.state, "${case.id}: an Action's id")
            assertFalse("2026-10-20" in case.state, "${case.id}: a timestamp")
            assertFalse("T09:00" in case.state, "${case.id}: a commitment's time of day")
        }
    }

    @Test
    fun `every case shares its family's goal and perception and cycle with its nothing_changed sibling`() {
        cases.forEach { case ->
            val state = case.parsed()
            val sibling = case.nothingChanged().parsed()
            assertEquals(sibling.goal, state.goal, case.id)
            assertEquals(sibling.perceived, state.perceived, case.id)
            assertEquals(sibling.cycleResults, state.cycleResults, case.id)
            assertEquals(case.familySpec().goal, state.goal, case.id)
        }
    }

    @Test
    fun `labels follow from the construction rules`() {
        cases.forEach { case ->
            val id = case.id
            val family = case.familySpec()
            val changes = case.parsed().changes
            val own = PerceptionDriftCorpus.OWN_NAMES
            if (case.kind() !in Kind.RENDER_EMPTY) {
                assertEquals(1, changes.listsTouched, "$id: a kind adds to exactly one list")
            }
            when (case.kind()) {
                Kind.NOTHING_CHANGED, Kind.COSMETIC_REORDER, Kind.OWN_RUN_ACTIONS_ONLY -> {
                    assertTrue(changes.isEmpty, "$id: every list empty")
                }
                Kind.GOAL_STATE_CHANGED -> {
                    val change = changes.goals.single()
                    assertEquals(family.dependency, change.goal, "$id: the dependency goal")
                    assertEquals("dependency", change.relation, "$id: the run's goal depends on it")
                    assertNotEquals(change.from, change.to, "$id: the state moved")
                    assertEquals("OPEN" to "ABANDONED", change.from to change.to, id)
                }
                Kind.COMMITMENT_ON_GOAL_OPENED -> {
                    val commitment = changes.opened.single()
                    assertEquals(family.goal, commitment.about, "$id: on the run's goal")
                    assertTrue(commitment.by !in own, "$id: by another subject")
                }
                Kind.COMMITMENT_ON_GOAL_RESOLVED -> {
                    val commitment = changes.resolved.single()
                    assertEquals(family.goal, commitment.about, "$id: on the run's goal")
                }
                Kind.LESSON_ABOUT_GOAL_SUBJECT -> {
                    val belief = changes.beliefs.single()
                    assertEquals("lesson", belief.kind, id)
                    assertEquals(family.goal, belief.about, "$id: about the goal")
                    assertTrue(belief.holder !in own, "$id: held by another subject")
                    assertTrue(family.target in belief.content, "$id: names the target")
                }
                Kind.CONSTRAINT_ADDED -> {
                    val belief = changes.beliefs.single()
                    assertTrue(belief.kind == "plan" || belief.kind == "task", "$id: plan or task, not ${belief.kind}")
                    assertEquals(family.goal, belief.about, "$id: about the goal")
                    assertTrue(belief.holder !in own, "$id: held by another subject")
                }
                Kind.OTHER_ACTOR_ON_GOAL_TARGET -> {
                    val action = changes.actions.single()
                    assertTrue(action.by !in own, "$id: by another subject")
                    assertTrue(family.target in action.did, "$id: on the target")
                    assertEquals("succeeded", action.result, id)
                }
                Kind.OBSERVATION_OF_TARGET_CHANGED -> {
                    val observation = changes.observations.single()
                    assertEquals(family.targetEntity, observation.about, id)
                    assertTrue(family.target in observation.content, "$id: names the target")
                }
                Kind.UNRELATED_SUBJECT_BELIEF -> {
                    val belief = changes.beliefs.single()
                    assertEquals(family.unrelatedGoal, belief.about, "$id: about the unrelated goal")
                    assertFalse(family.target in belief.content, "$id: does not name the target")
                    assertTrue(belief.holder !in own, "$id: held by another subject")
                }
                Kind.OBSERVATION_OF_UNRELATED_ENTITY -> {
                    val observation = changes.observations.single()
                    assertEquals(family.unrelatedEntity, observation.about, id)
                    assertFalse(family.target in observation.content, "$id: does not name the target")
                }
                Kind.UNRELATED_COMMITMENT -> {
                    val commitment = changes.opened.single()
                    assertEquals(family.unrelatedGoal, commitment.about, "$id: on the unrelated goal")
                    assertNotEquals(family.goal, commitment.about, id)
                }
                Kind.BOOKKEEPING_SUBJECTS_ADDED -> {
                    assertEquals(family.bookkeepingSubjects, changes.subjects, "$id: the two registered subjects")
                }
            }
        }
    }

    @Test
    fun `the render-empty kinds are empty because of the fold and not because nothing was built`() {
        PerceptionDriftCorpus.families.forEach { family ->
            val before = PerceptionDriftCorpus.before(family)

            val own = PerceptionDriftCorpus.after(family, Kind.OWN_RUN_ACTIONS_ONLY)
            assertTrue(own.actions.isNotEmpty(), "${family.id}: own actions were built")
            assertTrue(own.actions.all { it.actor in PerceptionDriftCorpus.OWN_ACTORS }, "${family.id}: by own seats")
            val ownDelta = FieldDelta.between(before, own, PerceptionDriftCorpus.OWN_ACTORS)
            assertTrue(ownDelta.isEmpty, "${family.id}: the fold leaves own actions out")
            val strangerDelta = FieldDelta.between(before, own, ownActors = emptySet())
            assertEquals(own.actions.size, strangerDelta.actionsByOthers.size, "${family.id}: no own actors keeps them")

            val cosmetic = PerceptionDriftCorpus.after(family, Kind.COSMETIC_REORDER)
            assertNotEquals(before.subjects, cosmetic.subjects, "${family.id}: the order moved")
            assertEquals(before.subjects.toSet(), cosmetic.subjects.toSet(), "${family.id}: the content did not")
            assertTrue(FieldDelta.between(before, cosmetic, PerceptionDriftCorpus.OWN_ACTORS).isEmpty, family.id)

            val nothing = PerceptionDriftCorpus.after(family, Kind.NOTHING_CHANGED)
            assertEquals(before.copy(asOfSequence = nothing.asOfSequence), nothing, family.id)
        }
    }

    @Test
    fun `re-running the generator reproduces the committed file byte for byte`() {
        val generated = PerceptionDriftCorpus.generate()
        assertEquals(generated, PerceptionDriftCorpus.decode(PerceptionDriftCorpus.encode(generated)))
        assertEquals(PerceptionDriftCorpus.encode(generated), PerceptionDriftCorpus.committedText())
    }

    private companion object {
        /** The closed set a changed goal's `relation` is one of (AMPR-486). */
        val RELATIONS: Set<String> = setOf("own", "dependency", "other")
    }
}
