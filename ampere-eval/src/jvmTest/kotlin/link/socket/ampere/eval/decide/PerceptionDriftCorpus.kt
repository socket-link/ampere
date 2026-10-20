package link.socket.ampere.eval.decide

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.field.Action
import link.socket.ampere.field.ActionId
import link.socket.ampere.field.ActionResult
import link.socket.ampere.field.ActionState
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Environment
import link.socket.ampere.field.Field
import link.socket.ampere.field.FieldDelta
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.propel.RunDrift
import link.socket.ampere.propel.RunPolicy
import link.socket.ampere.propel.RunQuestions
import link.socket.ampere.propel.asDecisionState

/**
 * The constructed perception-drift corpus (AMPR-482 task 1).
 *
 * Every case is a [RunDrift] rendered with [asDecisionState] at the run's default clip, over a
 * delta that [FieldDelta.between] folded from two real [Field] values — so a case's state is
 * byte-for-byte what a hosted run hands its decision transport after an executed cycle under
 * `PerceiveCadence.Record` (AMPR-481). The label is set by construction: each [Kind] is one
 * change made to a family's *before* Field to produce its *after*, and whether that change bears
 * on the goal is what the kind says. The validator checks that every committed case obeys its
 * kind's rule.
 *
 * ### Shape
 *
 * Eight goal [families][Family] — the eight of AMPR-422's corpus, each with one goal, one
 * prior perception (the sentence the host seat wrote at PERCEIVE) and one cycle's results —
 * crossed with fourteen kinds, **seven that bear on the goal and seven that are beside it**, so
 * the corpus is 112 cases and exactly half and half.
 *
 * | | kinds |
 * | -- | -- |
 * | bears | `goal_state_changed`, `commitment_on_goal_opened`, `commitment_on_goal_resolved`,
 *   `lesson_about_goal_subject`,
 *   `other_actor_on_goal_target`, `observation_of_target_changed`, `constraint_added` |
 * | beside | `unrelated_subject_belief`, `own_run_actions_only`, `cosmetic_reorder`, `observation_of_unrelated_entity`,
 *   `unrelated_commitment`, `bookkeeping_subjects_added`, `nothing_changed` |
 *
 * Every case of a family shares its goal, its perception, its cycle results and its *before*
 * Field; what separates a bears case from a beside one is only what the kind added. An arm that
 * keys on the goal, the family or the perception cannot do well here.
 *
 * ### What production leaves out, and so what renders equal
 *
 * Three beside kinds of every family render the **same state**, and the validator pins that:
 *
 * - `nothing_changed` is the fold of two equal Fields: every list under `changes` empty.
 * - `cosmetic_reorder` is the fold of the same Field read twice in a different list order.
 *   `FieldDelta.between` keys every list on identity, so the delta is empty.
 * - `own_run_actions_only` adds only Actions by this run's own seats. Production folds with
 *   `ownActors` = every seat of the roster, so `actionsByOthers` is empty and the delta is empty.
 *
 * That is the point of the fold (AMPR-479): the run's own work and the Field's bookkeeping are
 * taken out before a judge sees the state. The bench measures what is left for a judge to tell.
 *
 * ### What the state cannot show
 *
 * A [Commitment] renders as its committer's name, its `ticketId` and its due date — not its
 * `goal`. The two commitment kinds therefore tell bears from beside only by the ticket id's
 * text, so the corpus uses descriptive slugs (`records/file-signed-office-lease`). A consumer
 * whose ticket ids are opaque gives a judge nothing to read there; the report says so.
 * An observation renders as its entity reference and its content; production's `names`
 * hold Subjects and Goals only, so the reference is the raw value and the content carries
 * the meaning, as here.
 *
 * Constructed data only: no person, product or company is named. The other subjects are
 * roles — `Scheduler`, `Reviewer`, `Platform team` — and the run's own seats are `Host` and
 * `Worker`.
 */
internal object PerceptionDriftCorpus {

    /** The committed corpus, on the jvmTest classpath. */
    const val RESOURCE: String = "/decide/perception-drift/cases.json"

    /** The corpus directory under `src/jvmTest/resources`. */
    const val RESOURCE_DIR: String = "decide/perception-drift"

    /** The corpus subdirectory under [DIR_PROPERTY]'s directory. */
    const val SUBDIRECTORY: String = "perception-drift"

    /** The system property the generator and the live task set: the `decide` resources directory. */
    const val DIR_PROPERTY: String = GoalMetCorpus.DIR_PROPERTY

    /** The clip every state is rendered at: the run's own default. */
    val CLIP: Int = RunPolicy().judgmentResultClip

    /** The names the state renders this run's own seats as; what the own-and-cosmetic rule reads. */
    val OWN_NAMES: Set<String> = setOf("Host", "Worker")

    /** How a family's *after* Field differs from its *before*, and whether that bears on the goal. */
    enum class Kind(val bears: Boolean) {
        /** A goal the plan depends on moved `OPEN` to `ABANDONED`. */
        GOAL_STATE_CHANGED(bears = true),

        /** Another subject opened a commitment on the goal's ticket. */
        COMMITMENT_ON_GOAL_OPENED(bears = true),

        /** The commitment open on the goal's ticket was resolved. */
        COMMITMENT_ON_GOAL_RESOLVED(bears = true),

        /** Another subject recorded a `lesson` about the goal. */
        LESSON_ABOUT_GOAL_SUBJECT(bears = true),

        /** Another subject acted on the goal's target. */
        OTHER_ACTOR_ON_GOAL_TARGET(bears = true),

        /** An observation that the goal's target changed. */
        OBSERVATION_OF_TARGET_CHANGED(bears = true),

        /** Another subject recorded a `plan` or `task` belief about the goal: a constraint on it. */
        CONSTRAINT_ADDED(bears = true),

        /** Another subject recorded a `lesson` about an unrelated goal. */
        UNRELATED_SUBJECT_BELIEF(bears = false),

        /** Only this run's own seats acted; the fold leaves `actionsByOthers` empty. */
        OWN_RUN_ACTIONS_ONLY(bears = false),

        /** The same Field read in another list order; the fold is empty. */
        COSMETIC_REORDER(bears = false),

        /** An observation of an entity the goal is not about. */
        OBSERVATION_OF_UNRELATED_ENTITY(bears = false),

        /** Another subject opened a commitment on an unrelated ticket. */
        UNRELATED_COMMITMENT(bears = false),

        /** Two subjects were registered; nothing else moved. */
        BOOKKEEPING_SUBJECTS_ADDED(bears = false),

        /** The empty delta. */
        NOTHING_CHANGED(bears = false),
        ;

        /** The kind as it is written on a case. */
        val wireName: String = name.lowercase()

        companion object {
            fun of(wireName: String): Kind = entries.first { it.wireName == wireName }

            /** The kinds that render the same state as [NOTHING_CHANGED]. */
            val RENDER_EMPTY: Set<Kind> = setOf(NOTHING_CHANGED, COSMETIC_REORDER, OWN_RUN_ACTIONS_ONLY)
        }
    }

    /** One step the cycle executed, as the state renders it. */
    internal class StepSpec(val description: String, val result: String)

    /**
     * A goal family: one goal, what the host perceived, the cycle's two steps, and the texts
     * each kind adds to the Field.
     *
     * @property target The thing the goal is about, as a substring every text about it carries.
     * @property dependency A goal the plan depends on, held by another subject.
     * @property unrelatedGoal A goal of another subject that the run's goal has nothing to do with.
     * @property onGoalTicket The ticket id of a commitment on the goal; a descriptive slug.
     * @property unrelatedTicket The ticket id of a commitment on something else.
     * @property targetEntity The raw reference an observation of the target renders as.
     * @property unrelatedEntity The raw reference an observation of something else renders as.
     */
    internal class Family(
        val id: String,
        val goal: String,
        val target: String,
        val perceived: String,
        val steps: List<StepSpec>,
        val dependency: String,
        val unrelatedGoal: String,
        val lesson: String,
        val constraint: String,
        val constraintKind: BeliefKind,
        val otherAction: String,
        val targetObservation: String,
        val unrelatedBelief: String,
        val unrelatedObservation: String,
        val onGoalTicket: String,
        val unrelatedTicket: String,
        val bookkeepingSubjects: List<String>,
        val targetEntity: String,
        val unrelatedEntity: String,
    ) {
        init {
            require(steps.size == 2) { "$id needs two steps" }
            require(constraintKind == BeliefKind.PLAN || constraintKind == BeliefKind.TASK) {
                "$id: a constraint is PLAN or TASK"
            }
            require(goal.contains(target)) { "$id: the goal names its target" }
            listOf(lesson, otherAction, targetObservation).forEach {
                require(it.contains(target)) { "$id: a text about the target names it: '$it'" }
            }
            listOf(unrelatedBelief, unrelatedObservation, unrelatedGoal).forEach {
                require(!it.contains(target)) { "$id: a text beside the goal does not name the target: '$it'" }
            }
            require(bookkeepingSubjects.size == 2) { "$id needs two bookkeeping subjects" }
        }
    }

    private val fixedTime = Instant.parse("2026-10-20T00:00:00Z")
    private val dueDate = Instant.parse("2026-10-27T09:00:00Z")

    private const val SEQUENCE_BEFORE = 10L
    private const val SEQUENCE_AFTER = 11L

    // The run's own seats, and the other subjects every family's Field holds.
    val HOST: Ref<Subject> = Ref("seat-host")
    val WORKER: Ref<Subject> = Ref("seat-worker")
    val SCHEDULER: Ref<Subject> = Ref("agent-scheduler")
    val REVIEWER: Ref<Subject> = Ref("agent-reviewer")
    val PLATFORM_TEAM: Ref<Subject> = Ref("group-platform")

    val ROOT_GOAL: Ref<Goal> = Ref("goal-root")
    val DEPENDENCY_GOAL: Ref<Goal> = Ref("goal-dependency")
    val UNRELATED_GOAL: Ref<Goal> = Ref("goal-unrelated")

    /** The run's own actors, as production folds them: every seat of the roster. */
    val OWN_ACTORS: Set<Ref<Subject>> = setOf(HOST, WORKER)

    private val subjects: List<Subject> = listOf(
        Subject.Agent(id = Ref("seat-host"), name = "Host", provenance = provenance("event-subject-host")),
        Subject.Agent(id = Ref("seat-worker"), name = "Worker", provenance = provenance("event-subject-worker")),
        Subject.Agent(
            id = Ref("agent-scheduler"),
            name = "Scheduler",
            provenance = provenance("event-subject-scheduler"),
        ),
        Subject.Agent(id = Ref("agent-reviewer"), name = "Reviewer", provenance = provenance("event-subject-reviewer")),
        Subject.Group(
            id = Ref("group-platform"),
            name = "Platform team",
            provenance = provenance("event-subject-platform"),
        ),
    )

    /** The other subject each family's bears kinds act through, by family index. */
    private val actors: List<Ref<Subject>> = listOf(SCHEDULER, REVIEWER, PLATFORM_TEAM)

    val families: List<Family> = listOf(
        Family(
            id = "find-and-summarise",
            goal = "Find the latest version of the onboarding handbook and summarise its key points in three bullets",
            target = "the onboarding handbook",
            perceived = "The shared drive is reachable and the handbook folder holds three versions, the newest " +
                "edited last week.",
            steps = listOf(
                StepSpec(
                    "Search the shared drive for the onboarding handbook",
                    "Found the onboarding handbook: version 4 (final), 11 pages, last edited three days ago by its " +
                        "owner",
                ),
                StepSpec(
                    "Summarise the onboarding handbook",
                    "Summary of the onboarding handbook: • three changes since the previous version listed first " +
                        "• two items still need an owner by month end • one open question flagged for review",
                ),
            ),
            dependency = "Restore search indexing on the shared drive",
            unrelatedGoal = "Replace the lobby noticeboard",
            lesson = "A version 5 draft of the onboarding handbook lives in a separate folder and supersedes version 4",
            constraint = "Summaries of the onboarding handbook must be checked by its owner before they are shared",
            constraintKind = BeliefKind.PLAN,
            otherAction = "archived version 4 of the onboarding handbook and published version 5",
            targetObservation = "the onboarding handbook was replaced by a new version an hour ago; it is now 14 " +
                "pages, not 11",
            unrelatedBelief = "The lobby noticeboard needs a new frame before anything is posted on it",
            unrelatedObservation = "the lobby noticeboard listing gained two new postings",
            onGoalTicket = "docs/summarise-onboarding-handbook",
            unrelatedTicket = "facilities/lobby-noticeboard",
            bookkeepingSubjects = listOf("Index bot", "Docs team"),
            targetEntity = "canon/link-1/entity-1",
            unrelatedEntity = "canon/link-1/entity-2",
        ),
        Family(
            id = "schedule-and-notify",
            goal = "Schedule the design review with the three platform engineers for 30 minutes next week and " +
                "notify everyone invited",
            target = "the design review",
            perceived = "All three platform engineers share a free slot on Tuesday morning and their calendars are " +
                "readable.",
            steps = listOf(
                StepSpec(
                    "Find a free 30-minute slot next week for the design review",
                    "Free for every invitee of the design review: Tuesday 10:00–10:30; two other slots also open",
                ),
                StepSpec(
                    "Create the calendar event for the design review",
                    "Created the event for the design review on Tuesday 10:00–10:30 with every invitee attending",
                ),
            ),
            dependency = "Keep Tuesday mornings free of all-hands meetings",
            unrelatedGoal = "Order replacement chairs for the second floor",
            lesson = "One of the three engineers invited to the design review is away all of next week",
            constraint = "The design review must not be scheduled before the roadmap meeting on Monday",
            constraintKind = BeliefKind.TASK,
            otherAction = "moved the design review event to Thursday 14:00",
            targetObservation = "the design review event on Tuesday 10:00 was declined by two of the three invitees",
            unrelatedBelief = "The second-floor chairs come in two colours and the grey ones ship sooner",
            unrelatedObservation = "the chair order form gained a delivery date field",
            onGoalTicket = "calendar/schedule-design-review",
            unrelatedTicket = "office/second-floor-chairs",
            bookkeepingSubjects = listOf("Calendar bot", "Facilities team"),
            targetEntity = "canon/link-1/entity-3",
            unrelatedEntity = "canon/link-1/entity-4",
        ),
        Family(
            id = "file-and-link",
            goal = "File the signed office lease under Records/2026 and link it from the records overview page",
            target = "the signed office lease",
            perceived = "Records/2026 is writable and the records overview page has a 'This year' section with four " +
                "links.",
            steps = listOf(
                StepSpec(
                    "Move the signed office lease into Records/2026",
                    "Moved the signed office lease to Records/2026; the old location now holds a redirect stub",
                ),
                StepSpec(
                    "Add a link to the signed office lease on the records overview page",
                    "Added a link to Records/2026/the signed office lease under 'This year' on the records " +
                        "overview; saved",
                ),
            ),
            dependency = "Keep Records/2026 writable for the records seats",
            unrelatedGoal = "Rotate the kitchen cleaning schedule",
            lesson = "A second signature page of the signed office lease is stored as a separate file and must be " +
                "filed with it",
            constraint = "Nothing about the signed office lease may be linked from the overview page until the " +
                "records owner approves",
            constraintKind = BeliefKind.PLAN,
            otherAction = "moved the signed office lease out of Records/2026 into a legal hold folder",
            targetObservation = "the signed office lease in Records/2026 was replaced by an unsigned draft",
            unrelatedBelief = "The kitchen cleaning schedule works better in two-week blocks",
            unrelatedObservation = "the kitchen cleaning schedule gained a new column for the weekend",
            onGoalTicket = "records/file-signed-office-lease",
            unrelatedTicket = "kitchen/cleaning-schedule",
            bookkeepingSubjects = listOf("Records bot", "Legal team"),
            targetEntity = "canon/link-1/entity-5",
            unrelatedEntity = "canon/link-1/entity-6",
        ),
        Family(
            id = "convert-and-save",
            goal = "Convert the meeting notes to PDF and save the PDF beside the original file",
            target = "the meeting notes",
            perceived = "The meeting notes are one document in a folder with free space, and the converter is " +
                "installed.",
            steps = listOf(
                StepSpec(
                    "Convert the meeting notes to PDF",
                    "Converted the meeting notes to PDF: 9 pages, 1.2 MB, fonts embedded",
                ),
                StepSpec(
                    "Save the PDF of the meeting notes next to the original",
                    "Saved the PDF of the meeting notes in the original's folder; both files now listed side by side",
                ),
            ),
            dependency = "Keep the converter service running on the shared host",
            unrelatedGoal = "Choose a new wallpaper for the lobby display",
            lesson = "A font embedded in the meeting notes cannot be read by the converter, " +
                "so its PDF drops the headings",
            constraint = "Any PDF of the meeting notes must carry the meeting date in its file name",
            constraintKind = BeliefKind.TASK,
            otherAction = "edited the meeting notes and added two sections after the conversion",
            targetObservation = "the meeting notes were modified after the PDF was made; the document is now 12 pages",
            unrelatedBelief = "The lobby display wallpaper should avoid text, which is unreadable from the door",
            unrelatedObservation = "the lobby display's wallpaper folder gained three candidate images",
            onGoalTicket = "docs/convert-meeting-notes-to-pdf",
            unrelatedTicket = "lobby/display-wallpaper",
            bookkeepingSubjects = listOf("Converter bot", "Design team"),
            targetEntity = "canon/link-1/entity-7",
            unrelatedEntity = "canon/link-1/entity-8",
        ),
        Family(
            id = "compare-and-recommend",
            goal = "Compare the three storage plans on monthly cost and the stated requirement, and recommend one " +
                "with the reason",
            target = "the three storage plans",
            perceived = "All of the three storage plans publish monthly prices, and the requirement is 2 TB with " +
                "versioning.",
            steps = listOf(
                StepSpec(
                    "Gather the cost and capacity figures for the three storage plans",
                    "Figures for the three storage plans collected: monthly cost and capacity for each option, " +
                        "from each vendor's published pricing page",
                ),
                StepSpec(
                    "Recommend one of the three storage plans",
                    "Recommendation for the three storage plans: the second option, " +
                        "which meets the stated requirement " +
                        "at the lowest monthly cost; the cheapest option falls short on capacity",
                ),
            ),
            dependency = "Confirm the storage requirement of 2 TB with versioning",
            unrelatedGoal = "Pick a venue for the summer gathering",
            lesson = "The second of the three storage plans drops versioning on its monthly tier, which the " +
                "requirement needs",
            constraint = "A recommendation among the three storage plans must quote the yearly price as well as the " +
                "monthly one",
            constraintKind = BeliefKind.PLAN,
            otherAction = "raised the storage requirement that the three storage plans are compared against from 2 " +
                "TB to 5 TB",
            targetObservation = "the pricing page of one of the three storage plans changed; its monthly cost rose " +
                "by a third",
            unrelatedBelief = "The summer gathering draws more people when it is on a Friday",
            unrelatedObservation = "the venue list for the summer gathering gained a new entry",
            onGoalTicket = "procurement/compare-storage-plans",
            unrelatedTicket = "social/summer-gathering-venue",
            bookkeepingSubjects = listOf("Pricing bot", "Procurement team"),
            targetEntity = "canon/link-1/entity-9",
            unrelatedEntity = "canon/link-1/entity-10",
        ),
        Family(
            id = "fix-failing-check",
            goal = "Make the date-parsing test pass again without disabling it",
            target = "the date-parsing test",
            perceived = "The date-parsing test fails on one case at the month boundary and the branch is otherwise " +
                "green.",
            steps = listOf(
                StepSpec(
                    "Read the failure from the date-parsing test",
                    "The date-parsing test fails on one case: an off-by-one at the month boundary; " +
                        "the failing assertion and the stack trace are captured",
                ),
                StepSpec(
                    "Change the code so the date-parsing test passes",
                    "Edited the boundary handling behind the date-parsing test: one condition fixed, nothing skipped",
                ),
            ),
            dependency = "Keep the shared test runner available this week",
            unrelatedGoal = "Retire the old build dashboard",
            lesson = "The month-boundary case of the date-parsing test depends on the runner's time zone, not on " +
                "the parser",
            constraint = "Changes behind the date-parsing test need a second reviewer before they merge",
            constraintKind = BeliefKind.TASK,
            otherAction = "merged a change to the parser behind the date-parsing test on the main branch",
            targetObservation = "the date-parsing test now fails on three cases instead of one after a change on main",
            unrelatedBelief = "The old build dashboard still has two readers who need a replacement link",
            unrelatedObservation = "the old build dashboard shows a new deprecation banner",
            onGoalTicket = "ci/fix-date-parsing-test",
            unrelatedTicket = "ci/retire-build-dashboard",
            bookkeepingSubjects = listOf("CI bot", "Release team"),
            targetEntity = "canon/link-1/entity-11",
            unrelatedEntity = "canon/link-1/entity-12",
        ),
        Family(
            id = "tidy-and-report-counts",
            goal = "Archive everything older than 90 days in the shared downloads folder and report how many items " +
                "were archived",
            target = "the shared downloads folder",
            perceived = "The shared downloads folder holds 17 items older than 90 days and the archive location has " +
                "room.",
            steps = listOf(
                StepSpec(
                    "List the items older than 90 days in the shared downloads folder",
                    "Listed 17 items older than 90 days in the shared downloads folder; the oldest is 14 months old",
                ),
                StepSpec(
                    "Archive the old items in the shared downloads folder",
                    "Archived 17 items from the shared downloads folder; each is in the archive with its original " +
                        "path recorded",
                ),
            ),
            dependency = "Keep the archive location under its storage quota",
            unrelatedGoal = "Update the visitor sign-in sheet",
            lesson = "Three items in the shared downloads folder are referenced by the weekly report and must not " +
                "be archived",
            constraint = "Archiving in the shared downloads folder has to wait until the month-end audit has finished",
            constraintKind = BeliefKind.PLAN,
            otherAction = "restored four archived items to the shared downloads folder",
            targetObservation = "the shared downloads folder gained 30 items older than 90 days, copied in from a " +
                "backup",
            unrelatedBelief = "The visitor sign-in sheet needs a column for the host's name",
            unrelatedObservation = "the visitor sign-in sheet gained a new page for next month",
            onGoalTicket = "storage/archive-shared-downloads",
            unrelatedTicket = "reception/visitor-sign-in-sheet",
            bookkeepingSubjects = listOf("Archive bot", "Operations team"),
            targetEntity = "canon/link-1/entity-13",
            unrelatedEntity = "canon/link-1/entity-14",
        ),
        Family(
            id = "draft-and-send",
            goal = "Draft a note to the facilities team about the broken door sensor and send it",
            target = "the broken door sensor",
            perceived = "The facilities team's address is known and the door sensor has been reported broken since " +
                "Monday.",
            steps = listOf(
                StepSpec(
                    "Draft a note to the facilities team about the broken door sensor",
                    "Drafted the note about the broken door sensor: 96 words, three short paragraphs, plain tone, " +
                        "one clear ask",
                ),
                StepSpec(
                    "Send the note about the broken door sensor",
                    "Sent the note about the broken door sensor to the facilities team; the mail server accepted the " +
                        "message and the sent folder holds a copy",
                ),
            ),
            dependency = "Keep the facilities team's shared inbox open for reports",
            unrelatedGoal = "Order new name badges for the front desk",
            lesson = "The facilities team reads reports like the one about the broken door sensor from a ticket " +
                "queue, not from mail",
            constraint = "Notes about the broken door sensor have to go through the facilities team's lead first",
            constraintKind = BeliefKind.TASK,
            otherAction = "replaced the broken door sensor and closed its report",
            targetObservation = "the report about the broken door sensor was marked resolved by the facilities team",
            unrelatedBelief = "The front desk name badges should use the larger font",
            unrelatedObservation = "the name badge order form gained a quantity field",
            onGoalTicket = "facilities/note-broken-door-sensor",
            unrelatedTicket = "reception/front-desk-name-badges",
            bookkeepingSubjects = listOf("Mail bot", "Reception team"),
            targetEntity = "canon/link-1/entity-15",
            unrelatedEntity = "canon/link-1/entity-16",
        ),
    )

    /** The whole corpus, in a fixed order: family, then kind. */
    fun generate(): List<DecisionCase> = families.flatMap { family ->
        Kind.entries.map { kind -> case(family, kind) }
    }

    /** The case of [kind] for [family]. */
    fun case(family: Family, kind: Kind): DecisionCase {
        val before = before(family)
        val after = after(family, kind)
        val delta = FieldDelta.between(before, after, ownActors = OWN_ACTORS)
        // The later snapshot's names, over the earlier one's: what the run builds for the state.
        val names = buildMap {
            listOf(before, after).forEach { field ->
                field.subjects.forEach { put(it.id.value, it.name) }
                field.goals.forEach { put(it.goal.id.value, it.goal.description) }
            }
        }
        val drift = RunDrift(
            runId = "perception-drift-corpus",
            goal = Task.Step(id = "goal", status = TaskStatus.Pending, description = family.goal),
            cycle = CYCLE,
            perceived = family.perceived,
            delta = delta,
            cycleResults = family.steps.map { succeeded(it.description, it.result) },
            names = names,
        )
        return DecisionCase(
            id = "${family.id}/${kind.wireName}",
            kind = kind.wireName,
            state = drift.asDecisionState(CLIP),
            question = RunQuestions.perceptionDrift,
            expected = if (kind.bears) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY,
            family = family.id,
        )
    }

    /** The cycle every case is asked after. */
    const val CYCLE: Int = 2

    /** The other subject [family]'s bears kinds act through. */
    fun actorOf(family: Family): Ref<Subject> = actors[families.indexOf(family) % actors.size]

    /**
     * The Field the host seat perceived, before the cycle: the five subjects, the three goals
     * (the run's own, held open by the host; the dependency and the unrelated one, held by other
     * subjects), the host's own perception as a belief, and one commitment open on the goal.
     */
    fun before(family: Family): Field = Field(
        viewer = HOST,
        asOfSequence = SEQUENCE_BEFORE,
        subjects = subjects,
        relationships = emptyList(),
        goals = listOf(
            Field.HeldGoal(
                goal = Goal(id = ROOT_GOAL, description = family.goal, provenance = provenance("event-goal-root")),
                subject = HOST,
                state = Field.GoalState.OPEN,
            ),
            Field.HeldGoal(
                goal = Goal(
                    id = DEPENDENCY_GOAL,
                    description = family.dependency,
                    provenance = provenance("event-goal-dep"),
                ),
                subject = actorOf(family),
                state = Field.GoalState.OPEN,
            ),
            Field.HeldGoal(
                goal = Goal(
                    id = UNRELATED_GOAL,
                    description = family.unrelatedGoal,
                    provenance = provenance("event-goal-other"),
                ),
                subject = actorOf(family),
                state = Field.GoalState.OPEN,
            ),
        ),
        beliefs = listOf(
            belief(
                holder = HOST,
                subject = ROOT_GOAL,
                content = family.perceived,
                kind = BeliefKind.PERCEPTION,
                provenance = "event-perceive",
            ),
        ),
        observations = emptyList(),
        commitments = listOf(
            Commitment(
                committer = REVIEWER,
                ticketId = family.onGoalTicket,
                goal = ROOT_GOAL,
                due = dueDate,
                status = Commitment.Status.OPEN,
                provenance = provenance("event-commit-existing"),
            ),
        ),
        actions = emptyList(),
    )

    /** The Field OBSERVE read after the cycle: [before] with [kind]'s one change applied. */
    fun after(family: Family, kind: Kind): Field {
        val before = before(family)
        val actor = actorOf(family)
        val base = before.copy(asOfSequence = SEQUENCE_AFTER)
        return when (kind) {
            Kind.NOTHING_CHANGED -> base
            Kind.COSMETIC_REORDER -> base.copy(
                subjects = base.subjects.reversed(),
                goals = base.goals.reversed(),
                beliefs = base.beliefs.reversed(),
                commitments = base.commitments.reversed(),
            )
            Kind.OWN_RUN_ACTIONS_ONLY -> base.copy(
                actions = family.steps.mapIndexed { index, step ->
                    action(
                        id = "action-own-$index",
                        actor = if (index == 0) HOST else WORKER,
                        description = step.description.replaceFirstChar { it.lowercase() },
                    )
                },
            )
            Kind.GOAL_STATE_CHANGED -> base.copy(
                goals = base.goals.map { held ->
                    if (held.goal.id == DEPENDENCY_GOAL) held.copy(state = Field.GoalState.ABANDONED) else held
                },
            )
            Kind.COMMITMENT_ON_GOAL_OPENED -> base.copy(
                commitments = base.commitments + Commitment(
                    committer = actor,
                    ticketId = family.onGoalTicket,
                    goal = ROOT_GOAL,
                    due = dueDate,
                    status = Commitment.Status.OPEN,
                    provenance = provenance("event-commit-opened"),
                ),
            )
            Kind.COMMITMENT_ON_GOAL_RESOLVED -> base.copy(
                commitments = base.commitments.map {
                    it.copy(
                        status = Commitment.Status.RESOLVED,
                        resolvedBy = "event-commit-resolved",
                        resolvedAt = fixedTime,
                    )
                },
            )
            Kind.UNRELATED_COMMITMENT -> base.copy(
                commitments = base.commitments + Commitment(
                    committer = actor,
                    ticketId = family.unrelatedTicket,
                    goal = UNRELATED_GOAL,
                    due = dueDate,
                    status = Commitment.Status.OPEN,
                    provenance = provenance("event-commit-unrelated"),
                ),
            )
            Kind.LESSON_ABOUT_GOAL_SUBJECT -> base.copy(
                beliefs = base.beliefs +
                    belief(actor, ROOT_GOAL, family.lesson, BeliefKind.LESSON, "event-belief-lesson"),
            )
            Kind.CONSTRAINT_ADDED -> base.copy(
                beliefs = base.beliefs +
                    belief(actor, ROOT_GOAL, family.constraint, family.constraintKind, "event-belief-constraint"),
            )
            Kind.UNRELATED_SUBJECT_BELIEF -> base.copy(
                beliefs = base.beliefs +
                    belief(actor, UNRELATED_GOAL, family.unrelatedBelief, BeliefKind.LESSON, "event-belief-unrelated"),
            )
            Kind.OTHER_ACTOR_ON_GOAL_TARGET -> base.copy(
                actions = listOf(action(id = "action-other", actor = actor, description = family.otherAction)),
            )
            Kind.OBSERVATION_OF_TARGET_CHANGED -> base.copy(
                observations = listOf(
                    observation(family.targetEntity, family.targetObservation, "event-observe-target"),
                ),
            )
            Kind.OBSERVATION_OF_UNRELATED_ENTITY -> base.copy(
                observations = listOf(
                    observation(family.unrelatedEntity, family.unrelatedObservation, "event-observe-other"),
                ),
            )
            Kind.BOOKKEEPING_SUBJECTS_ADDED -> base.copy(
                subjects = base.subjects + family.bookkeepingSubjects.mapIndexed { index, name ->
                    val slug = name.lowercase().replace(' ', '-')
                    if (index == 0) {
                        Subject.Agent(
                            id = Ref("agent-$slug"),
                            name = name,
                            provenance = provenance("event-subject-$slug"),
                        )
                    } else {
                        Subject.Group(
                            id = Ref("group-$slug"),
                            name = name,
                            provenance = provenance("event-subject-$slug"),
                        )
                    }
                },
            )
        }
    }

    private fun provenance(createdBy: String): Provenance = Provenance(createdBy = createdBy, createdAt = fixedTime)

    private fun belief(
        holder: Ref<Subject>,
        subject: Ref<Primitive>,
        content: String,
        kind: BeliefKind,
        provenance: String,
    ): Belief = Belief.Settled(
        holder = holder,
        subject = subject,
        content = content,
        kind = kind,
        confidence = 0.9,
        sensitivity = Sensitivity.PUBLIC,
        provenance = provenance(provenance),
    )

    private fun observation(entity: String, content: String, provenance: String): Belief.Settled = Belief.Settled(
        holder = HOST,
        subject = Ref<Environment>(entity),
        content = content,
        kind = BeliefKind.OBSERVATION,
        confidence = 1.0,
        sensitivity = Sensitivity.PUBLIC,
        provenance = provenance(provenance),
    )

    private fun action(id: String, actor: Ref<Subject>, description: String): Action = Action(
        id = ActionId(id),
        actor = actor,
        description = description,
        state = ActionState.TAKEN,
        reversibility = null,
        cost = null,
        result = ActionResult.Succeeded,
        provenance = provenance("event-$id"),
    )

    private fun succeeded(description: String, details: String): StepOutcome.Success = StepOutcome.Success(
        id = "outcome",
        stepDescription = description,
        startTimestamp = fixedTime,
        endTimestamp = fixedTime,
        details = details,
    )

    private val serializer = ListSerializer(DecisionCase.serializer())

    /** The corpus as the committed file's text: pretty JSON, trailing newline. */
    fun encode(cases: List<DecisionCase>): String = DecisionCorpusJson.encodeToString(serializer, cases) + "\n"

    /** The inverse of [encode]. */
    fun decode(text: String): List<DecisionCase> = DecisionCorpusJson.decodeFromString(serializer, text)

    /**
     * The committed corpus's bytes, as text.
     *
     * From the classpath in an ordinary `jvmTest` run. When the generator task set
     * [DIR_PROPERTY], the corpus is regenerated and written there first and that file is read —
     * so `generatePerceptionDriftCorpus` is "regenerate, then validate", and the file is never
     * written by anything else.
     */
    fun committedText(): String {
        outputDirectory()?.let { directory ->
            val cases = generate()
            write(directory, cases)
            println("[perception-drift-corpus] wrote ${cases.size} case(s) to ${directory.resolve("cases.json")}")
            return Files.readString(directory.resolve("cases.json"))
        }
        val stream = requireNotNull(PerceptionDriftCorpus::class.java.getResourceAsStream(RESOURCE)) {
            "Missing corpus resource '$RESOURCE'. Generate it with ./gradlew :ampere-eval:generatePerceptionDriftCorpus"
        }
        return stream.use { it.readBytes().decodeToString() }
    }

    /** The committed corpus. */
    fun load(): List<DecisionCase> = decode(committedText())

    /** The directory the generator and the live task write under, when [DIR_PROPERTY] is set. */
    fun outputDirectory(): Path? = System.getProperty(DIR_PROPERTY)?.let { Paths.get(it).resolve(SUBDIRECTORY) }

    /** Writes [cases] as `cases.json` under [directory]. */
    fun write(directory: Path, cases: List<DecisionCase>) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("cases.json"), encode(cases))
    }
}
