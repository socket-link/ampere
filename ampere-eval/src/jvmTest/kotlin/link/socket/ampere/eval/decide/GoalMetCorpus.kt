package link.socket.ampere.eval.decide

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.propel.RunObservation
import link.socket.ampere.propel.RunPolicy
import link.socket.ampere.propel.RunQuestions
import link.socket.ampere.propel.asDecisionState

/**
 * The constructed goal-met corpus (AMPR-422 task 2).
 *
 * Every case is a [RunObservation] rendered with [asDecisionState] at the run's default clip,
 * so a case's state is byte-for-byte what a hosted run would hand its decision transport. The
 * label is set by construction: each [Kind] is a rule for turning a family's clean run into a
 * met or unmet one, and the validator checks that every committed case obeys its kind's rule.
 *
 * ### Shape
 *
 * Eight goal [families][Family], each with six [targets][Family.targets] (the thing the goal
 * is about) and two or three steps. The three **met** kinds are rendered for all six targets
 * and the six **unmet** kinds for the first three, so the corpus is exactly half met:
 *
 * | | kinds | per family | total |
 * | -- | -- | -- | -- |
 * | met | `clean`, `redundant_failure`, `terse` | 3 × 6 | 144 |
 * | unmet | `partial`, `wrong_target`, `empty_result`, `error_as_success`, `unverifiable`, `all_failed` | 6 × 3
 *   | 144 |
 *
 * A met and an unmet case of the same target therefore share a goal and most of their text;
 * what separates them is only what the rule changed. That is deliberate: an arm that keys on
 * the goal, the step names or the family cannot do well here.
 *
 * ### What the rules change
 *
 * The first step of every run is the lookup — "find", "list", "read the failure" — and the
 * steps after it are the ones that do the goal. The unmet kinds `empty_result`,
 * `error_as_success` and `unverifiable` leave the lookup intact and alter every later step,
 * so a three-step family does not keep a middle step that already completed the goal.
 *
 * Constructed data only: no person, product or company is named anywhere in it.
 */
internal object GoalMetCorpus {

    /** The committed corpus, as a classpath resource. */
    const val RESOURCE: String = "/decide/goal-met/cases.json"

    /** The directory the committed corpus and the recorded answers live under, relative to the resources root. */
    const val RESOURCE_DIR: String = "decide/goal-met"

    /** The system property the generator and the live task take their output directory from. */
    const val DIR_PROPERTY: String = "ampere.eval.decideDir"

    /** The clip every state is rendered at: the run's own default. */
    val CLIP: Int = RunPolicy().judgmentResultClip

    /** How a case is built from a family's clean run, and whether that leaves the goal met. */
    enum class Kind(val met: Boolean) {
        /** Every step succeeded and its result shows the outcome. */
        CLEAN(met = true),

        /** One step failed and a retry of the same step succeeded; every part is covered. Cycle 2. */
        REDUNDANT_FAILURE(met = true),

        /** Every step succeeded with a short result that still states the outcome. */
        TERSE(met = true),

        /** The last step never ran: the run stopped one part short. */
        PARTIAL(met = false),

        /** Every step succeeded, on a different target than the goal names. */
        WRONG_TARGET(met = false),

        /** The lookup succeeded; every later step succeeded with an empty result. */
        EMPTY_RESULT(met = false),

        /** The lookup succeeded; every later step is marked succeeded but its text is an error or a refusal. */
        ERROR_AS_SUCCESS(met = false),

        /** The lookup succeeded; every later step's text claims an attempt and shows no outcome. */
        UNVERIFIABLE(met = false),

        /** Every step failed. Cycle 2. */
        ALL_FAILED(met = false),
        ;

        /** The kind as it is written on a case. */
        val wireName: String = name.lowercase()

        companion object {
            fun of(wireName: String): Kind = entries.first { it.wireName == wireName }
        }
    }

    /** One step of a family's run, as text templates over the target `t`. */
    internal class StepSpec(
        val description: (String) -> String,
        val full: (String) -> String,
        val terse: (String) -> String,
        val failure: (String) -> String,
        /** Three error-or-refusal texts a step marked succeeded might carry; chosen by target index. */
        val errorAsSuccess: List<(String) -> String> = emptyList(),
        /** Three hedged texts that claim an attempt and show no outcome; chosen by target index. */
        val unverifiable: List<(String) -> String> = emptyList(),
    )

    /** A goal family: the goal template, six targets, and the steps of its clean run. */
    internal class Family(
        val id: String,
        val goal: (String) -> String,
        val targets: List<String>,
        val steps: List<StepSpec>,
    ) {
        init {
            require(targets.size == TARGETS_PER_FAMILY) { "$id needs $TARGETS_PER_FAMILY targets" }
            require(steps.size in 2..3) { "$id needs two or three steps" }
            steps.drop(1).forEach { step ->
                require(step.errorAsSuccess.size == VARIANTS && step.unverifiable.size == VARIANTS) {
                    "$id: every step after the lookup needs $VARIANTS error and $VARIANTS unverifiable texts"
                }
            }
        }
    }

    const val TARGETS_PER_FAMILY: Int = 6
    const val UNMET_TARGETS_PER_FAMILY: Int = 3
    private const val VARIANTS = 3

    /**
     * Matches the hedged openers and modal claims an `unverifiable` result is written with.
     * The validator checks every `unverifiable` case against it, and the rule is this pattern.
     */
    val UNVERIFIABLE_PATTERN: Regex = Regex(
        """^(Attempted|Tried|Started|Ran|Triggered|Queued|Submitted|Looked|Pressed|Requested)\b""" +
            """|\bshould\b|\bwill (follow|be)\b""",
    )

    /**
     * Matches the error and refusal vocabulary an `error_as_success` result is written with.
     * Every such case's altered steps match it; the validator checks the rule, not the texts.
     */
    val ERROR_PATTERN: Regex = Regex(
        """(?i)\b(\w*error|forbidden|denied|refused|rejected|unable|cannot|can't|couldn't|could not|""" +
            """not (able|allowed|permitted|writable)|fails?|failed|traceback|exception|aborted)\b|\b[45]\d\d\b""",
    )

    private val fixedTime = Instant.parse("2026-10-11T00:00:00Z")

    val families: List<Family> = listOf(
        Family(
            id = "find-and-summarise",
            goal = { t -> "Find the latest version of $t and summarise its key points in three bullets" },
            targets = listOf(
                "the quarterly budget review",
                "the onboarding handbook",
                "the incident retrospective for the March outage",
                "the vendor comparison memo",
                "the draft hiring plan",
                "the release checklist",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "Search the shared drive for $t" },
                    full = { t -> "Found $t: version 4 (final), 11 pages, last edited three days ago by its owner" },
                    terse = { t -> "found $t v4" },
                    failure = { t -> "Search for $t failed: the shared drive returned 503 Service Unavailable" },
                ),
                StepSpec(
                    description = { t -> "Summarise $t" },
                    full = { t ->
                        "Summary of $t: • the three changes since the previous version are listed first " +
                            "• two items still need an owner by month end • one open question is flagged for review"
                    },
                    terse = { t -> "summarised $t: 3 bullets" },
                    failure = { t -> "Could not open $t: the file is locked by another editor" },
                    errorAsSuccess = listOf(
                        { t -> "Error: cannot read $t — 403 Forbidden" },
                        { t -> "I'm not able to summarise $t because the document could not be loaded." },
                        { t -> "Traceback (most recent call last): FileNotFoundError: $t" },
                    ),
                    unverifiable = listOf(
                        { t -> "Attempted to summarise $t." },
                        { t -> "Started reading $t; the summary should follow." },
                        { t -> "Ran the summariser on $t." },
                    ),
                ),
            ),
        ),
        Family(
            id = "schedule-and-notify",
            goal = { t -> "Schedule $t for 30 minutes next week and notify everyone invited" },
            targets = listOf(
                "a design review with the three platform engineers",
                "a budget check-in with the finance partner",
                "a retrospective with the on-call rotation",
                "a planning session with the two product leads",
                "a hand-over with the incoming intern",
                "a vendor call with the procurement contact",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "Find a free 30-minute slot next week for $t" },
                    full = { t -> "Free for every invitee of $t: Tuesday 10:00–10:30; two other slots also open" },
                    terse = { t -> "slot for $t: Tue 10:00" },
                    failure = { t -> "Calendar lookup for $t failed: no permission to read one invitee's calendar" },
                ),
                StepSpec(
                    description = { t -> "Create the calendar event for $t" },
                    full = { t -> "Created the event for $t on Tuesday 10:00–10:30 with every invitee attending" },
                    terse = { t -> "event created for $t" },
                    failure = { t -> "Event creation for $t rejected: the calendar returned 409 Conflict" },
                    errorAsSuccess = listOf(
                        { t -> "Error 409: the calendar refused to create the event for $t" },
                        { t -> "I couldn't create the event for $t; the calendar is read-only for this account." },
                        { t -> "CalendarError: creation of the event for $t rejected" },
                    ),
                    unverifiable = listOf(
                        { t -> "Attempted to create the event for $t." },
                        { t -> "The event for $t should exist now." },
                        { t -> "Submitted the event request for $t." },
                    ),
                ),
                StepSpec(
                    description = { t -> "Notify the invitees of $t" },
                    full = { t -> "Sent the invitation for $t to every attendee; delivery confirmed for each" },
                    terse = { t -> "invites sent for $t" },
                    failure = { t -> "Notification for $t failed: the mail relay refused the connection" },
                    errorAsSuccess = listOf(
                        { t -> "Error 401: the mail relay rejected the credentials while sending invitations for $t" },
                        { t -> "I can't send the invitations for $t — mail is not permitted for this account." },
                        { t -> "SMTP error: 550 relay access denied (invitations for $t)" },
                    ),
                    unverifiable = listOf(
                        { t -> "Tried to send the invitations for $t." },
                        { t -> "The invitees should now have the invitation for $t." },
                        { t -> "Queued the notification for $t." },
                    ),
                ),
            ),
        ),
        Family(
            id = "file-and-link",
            goal = { t -> "File $t under Records/2026 and link it from the records overview page" },
            targets = listOf(
                "the third-quarter cost report",
                "the signed office lease",
                "the accessibility audit",
                "the data-retention policy",
                "the supplier risk assessment",
                "the annual training record",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "Move $t into Records/2026" },
                    full = { t -> "Moved $t to Records/2026; the old location now holds a redirect stub" },
                    terse = { t -> "moved $t" },
                    failure = { t -> "Move of $t failed: Records/2026 is read-only for this account" },
                ),
                StepSpec(
                    description = { t -> "Add a link to $t on the records overview page" },
                    full = { t -> "Added a link to Records/2026/$t under 'This year' on the records overview; saved" },
                    terse = { t -> "linked $t from overview" },
                    failure = { t -> "Page edit for $t failed: the overview page is locked by another editor" },
                    errorAsSuccess = listOf(
                        { t -> "Error: 403 Forbidden while editing the records overview page for $t" },
                        { t -> "Unable to edit the overview page; the link to $t was not added." },
                        { t -> "PermissionError: write denied on the records overview page ($t)" },
                    ),
                    unverifiable = listOf(
                        { t -> "Attempted to add the link to $t." },
                        { t -> "The link to $t should now be on the overview page." },
                        { t -> "Submitted the page edit for $t." },
                    ),
                ),
            ),
        ),
        Family(
            id = "convert-and-save",
            goal = { t -> "Convert $t to PDF and save the PDF beside the original file" },
            targets = listOf(
                "the meeting notes",
                "the slide outline",
                "the expense table",
                "the project brief",
                "the interview rubric",
                "the floor plan sketch",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "Convert $t to PDF" },
                    full = { t -> "Converted $t to PDF: 9 pages, 1.2 MB, fonts embedded" },
                    terse = { t -> "converted $t to pdf" },
                    failure = { t -> "Conversion of $t failed: the converter exited with status 1" },
                ),
                StepSpec(
                    description = { t -> "Save the PDF of $t next to the original" },
                    full = { t -> "Saved the PDF of $t in the original's folder; both files now listed side by side" },
                    terse = { t -> "saved pdf of $t beside original" },
                    failure = { t -> "Save of the PDF of $t failed: the folder is out of quota" },
                    errorAsSuccess = listOf(
                        { t -> "Error: ENOSPC — no space left on device while saving the PDF of $t" },
                        { t -> "I couldn't save the PDF of $t; the destination folder is not writable." },
                        { t -> "write error: disk quota exceeded (PDF of $t)" },
                    ),
                    unverifiable = listOf(
                        { t -> "Ran the save command for the PDF of $t." },
                        { t -> "The PDF of $t should be beside the original now." },
                        { t -> "Attempted to save the PDF of $t." },
                    ),
                ),
            ),
        ),
        Family(
            id = "compare-and-recommend",
            goal = { t -> "Compare $t on monthly cost and the stated requirement, and recommend one with the reason" },
            targets = listOf(
                "the three storage plans",
                "the two conferencing tools",
                "the four laptop models",
                "the three courier services",
                "the two payroll providers",
                "the three monitoring options",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "Gather the cost and capacity figures for $t" },
                    full = { t ->
                        "Figures for $t collected: monthly cost and capacity for each option, " +
                            "from each vendor's published pricing page"
                    },
                    terse = { t -> "figures gathered for $t" },
                    failure = { t -> "Could not gather the figures for $t: two pricing pages timed out" },
                ),
                StepSpec(
                    description = { t -> "Recommend one of $t" },
                    full = { t ->
                        "Recommendation for $t: the second option — it meets the stated requirement at the lowest " +
                            "monthly cost; the cheapest option falls short on capacity"
                    },
                    terse = { t -> "recommend the second option of $t: cheapest that meets the requirement" },
                    failure = { t -> "No recommendation for $t: the comparison table was empty" },
                    errorAsSuccess = listOf(
                        { t -> "Error: comparison of $t aborted — pricing data missing for two options" },
                        { t -> "I can't recommend one of $t without the figures, which failed to load." },
                        { t -> "ValueError: empty comparison table for $t" },
                    ),
                    unverifiable = listOf(
                        { t -> "Looked at $t." },
                        { t -> "A recommendation for $t will follow once the figures are checked." },
                        { t -> "Started comparing $t." },
                    ),
                ),
            ),
        ),
        Family(
            id = "fix-failing-check",
            goal = { t -> "Make $t pass again without disabling it" },
            targets = listOf(
                "the date-parsing test",
                "the schema validation check",
                "the link checker",
                "the type check on the billing module",
                "the formatting check",
                "the dependency audit",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "Read the failure from $t" },
                    full = { t ->
                        "$t fails on one case: an off-by-one at the month boundary; " +
                            "the failing assertion and the stack trace are captured"
                    },
                    terse = { t -> "read the failure of $t" },
                    failure = { t -> "Could not read the failure: the log for $t is missing" },
                ),
                StepSpec(
                    description = { t -> "Change the code so $t passes" },
                    full = { t -> "Edited the boundary handling behind $t: one condition fixed, nothing skipped" },
                    terse = { t -> "fixed the boundary condition behind $t" },
                    failure = { t -> "Edit behind $t rejected: the file is read-only" },
                    errorAsSuccess = listOf(
                        { t -> "Error: the file behind $t could not be written (read-only)" },
                        { t -> "I can't edit the code behind $t from this account." },
                        { t -> "PermissionError: editing refused for the file behind $t" },
                    ),
                    unverifiable = listOf(
                        { t -> "Tried a change behind $t." },
                        { t -> "Looked at the code behind $t." },
                        { t -> "Started editing the handling behind $t." },
                    ),
                ),
                StepSpec(
                    description = { t -> "Rerun $t" },
                    full = { t -> "$t passes: 42 of 42 cases green, none skipped or disabled" },
                    terse = { t -> "$t green, nothing disabled" },
                    failure = { t -> "Rerun of $t failed: it still fails on the same case" },
                    errorAsSuccess = listOf(
                        { t -> "Error: $t still fails — 1 of 42 cases red" },
                        { t -> "I was unable to get $t to pass; the failure persists." },
                        { t -> "FAIL: $t (1 failure)" },
                    ),
                    unverifiable = listOf(
                        { t -> "Ran $t again." },
                        { t -> "$t should pass now." },
                        { t -> "Triggered a rerun of $t." },
                    ),
                ),
            ),
        ),
        Family(
            id = "tidy-and-report-counts",
            goal = { t -> "Archive everything older than 90 days in $t and report how many items were archived" },
            targets = listOf(
                "the shared downloads folder",
                "the stale feature branches",
                "the closed support tickets",
                "the unused calendar rooms",
                "the expired access grants",
                "the orphaned build artefacts",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "List the items older than 90 days in $t" },
                    full = { t -> "Listed 17 items older than 90 days in $t; the oldest is 14 months old" },
                    terse = { t -> "17 old items in $t" },
                    failure = { t -> "Listing $t failed: no read access" },
                ),
                StepSpec(
                    description = { t -> "Archive the old items in $t" },
                    full = { t -> "Archived 17 items from $t; each is in the archive with its original path recorded" },
                    terse = { t -> "archived 17 from $t" },
                    failure = { t -> "Archive step for $t failed: the archive location is full" },
                    errorAsSuccess = listOf(
                        { t -> "Error: archive of $t aborted after 0 items — permission denied" },
                        { t -> "I can't archive items in $t; this account has no write access there." },
                        { t -> "IOError: archive location for $t unavailable; nothing archived" },
                    ),
                    unverifiable = listOf(
                        { t -> "Started archiving $t." },
                        { t -> "The old items in $t should be archived now." },
                        { t -> "Attempted to archive the old items in $t." },
                    ),
                ),
                StepSpec(
                    description = { t -> "Report the count for $t" },
                    full = { t -> "Report: 17 of 17 old items in $t archived, 0 skipped" },
                    terse = { t -> "17/17 archived in $t" },
                    failure = { t -> "Report for $t failed: the count could not be written" },
                    errorAsSuccess = listOf(
                        { t -> "Error: no count available for $t — the archive step did not complete" },
                        { t -> "I can't report a count for $t; nothing was archived." },
                        { t -> "KeyError: count missing for $t" },
                    ),
                    unverifiable = listOf(
                        { t -> "A count for $t will follow." },
                        { t -> "The report for $t should be ready soon." },
                        { t -> "Queued the report for $t." },
                    ),
                ),
            ),
        ),
        Family(
            id = "draft-and-send",
            goal = { t -> "Draft $t and send it" },
            targets = listOf(
                "a note to the facilities team about the broken door sensor",
                "a reminder to the reviewers about the open pull requests",
                "an update to the sponsors about the schedule slip",
                "a thank-you to the volunteers from the launch event",
                "a request to the landlord about the parking allocation",
                "an announcement to the team about the new on-call rota",
            ),
            steps = listOf(
                StepSpec(
                    description = { t -> "Draft $t" },
                    full = { t -> "Drafted $t: 96 words, three short paragraphs, plain tone, one clear ask" },
                    terse = { t -> "drafted $t" },
                    failure = { t -> "Draft of $t failed: the template service returned an empty body" },
                ),
                StepSpec(
                    description = { t -> "Send $t" },
                    full = { t ->
                        "Sent $t to the intended recipients; the mail server accepted the message " +
                            "and the sent folder holds a copy"
                    },
                    terse = { t -> "sent $t" },
                    failure = { t -> "Send of $t failed: the mail server rejected the message with 554" },
                    errorAsSuccess = listOf(
                        { t -> "Error 554: the mail server refused $t" },
                        { t -> "I'm not allowed to send $t from this account." },
                        { t -> "smtp: 421 service not available — $t was not sent" },
                    ),
                    unverifiable = listOf(
                        { t -> "Attempted to send $t." },
                        { t -> "Pressed send on $t; it should have gone out." },
                        { t -> "Requested delivery of $t." },
                    ),
                ),
            ),
        ),
    )

    /** The whole corpus, in a fixed order: family, then kind, then target. */
    fun generate(): List<DecisionCase> = families.flatMap { family ->
        Kind.entries.flatMap { kind ->
            val targets = if (kind.met) TARGETS_PER_FAMILY else UNMET_TARGETS_PER_FAMILY
            (0 until targets).map { index -> case(family, kind, index) }
        }
    }

    /** The case of [kind] for [family]'s target at [index]. */
    fun case(family: Family, kind: Kind, index: Int): DecisionCase {
        val target = family.targets[index]
        val steps = family.steps
        val outcomes: List<StepOutcome>
        var cycle = 1
        when (kind) {
            Kind.CLEAN -> outcomes = steps.map { succeeded(it.description(target), it.full(target)) }
            Kind.TERSE -> outcomes = steps.map { succeeded(it.description(target), it.terse(target)) }
            Kind.REDUNDANT_FAILURE -> {
                val retried = index % steps.size
                cycle = 2
                outcomes = steps.flatMapIndexed { i, step ->
                    val suffix = if (i == retried) " (retry)" else ""
                    val success = succeeded(step.description(target) + suffix, step.full(target))
                    if (i == retried) {
                        listOf(failed(step.description(target), step.failure(target)), success)
                    } else {
                        listOf(success)
                    }
                }
            }
            Kind.PARTIAL -> outcomes = steps.dropLast(1).map { succeeded(it.description(target), it.full(target)) }
            Kind.WRONG_TARGET -> {
                val other = family.targets[(index + 1) % TARGETS_PER_FAMILY]
                outcomes = steps.map { succeeded(it.description(target), it.full(other)) }
            }
            Kind.EMPTY_RESULT -> outcomes = steps.mapIndexed { i, step ->
                succeeded(step.description(target), if (i == 0) step.full(target) else "")
            }
            Kind.ERROR_AS_SUCCESS -> outcomes = steps.mapIndexed { i, step ->
                val text = if (i == 0) step.full(target) else step.errorAsSuccess[index % VARIANTS](target)
                succeeded(step.description(target), text)
            }
            Kind.UNVERIFIABLE -> outcomes = steps.mapIndexed { i, step ->
                val text = if (i == 0) step.full(target) else step.unverifiable[index % VARIANTS](target)
                succeeded(step.description(target), text)
            }
            Kind.ALL_FAILED -> {
                cycle = 2
                outcomes = steps.map { failed(it.description(target), it.failure(target)) }
            }
        }
        val observation = RunObservation(
            runId = "goal-met-corpus",
            goal = Task.Step(id = "goal", status = TaskStatus.Pending, description = family.goal(target)),
            cycle = cycle,
            priorResults = outcomes,
        )
        return DecisionCase(
            id = "${family.id}/${kind.wireName}/$index",
            kind = kind.wireName,
            state = observation.asDecisionState(CLIP),
            question = RunQuestions.goalMet,
            expected = if (kind.met) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY,
            family = family.id,
        )
    }

    private fun succeeded(description: String, details: String): StepOutcome.Success = StepOutcome.Success(
        id = "outcome",
        stepDescription = description,
        startTimestamp = fixedTime,
        endTimestamp = fixedTime,
        details = details,
    )

    private fun failed(description: String, error: String): StepOutcome.Failure = StepOutcome.Failure(
        id = "outcome",
        stepDescription = description,
        startTimestamp = fixedTime,
        endTimestamp = fixedTime,
        error = error,
    )

    private val serializer = ListSerializer(DecisionCase.serializer())

    /** The corpus as the committed file's text: pretty JSON, trailing newline. */
    fun encode(cases: List<DecisionCase>): String = DecisionCorpusJson.encodeToString(serializer, cases) + "\n"

    /** The inverse of [encode]. */
    fun decode(text: String): List<DecisionCase> = DecisionCorpusJson.decodeFromString(serializer, text)

    /** The committed corpus's bytes, as text. */
    fun committedText(): String {
        val stream = requireNotNull(GoalMetCorpus::class.java.getResourceAsStream(RESOURCE)) {
            "Missing corpus resource '$RESOURCE'. Generate it with ./gradlew :ampere-eval:generateGoalMetCorpus"
        }
        return stream.use { it.readBytes().decodeToString() }
    }

    /** The committed corpus. */
    fun load(): List<DecisionCase> = decode(committedText())

    /** The directory the generator and the live task write under, when [DIR_PROPERTY] is set. */
    fun outputDirectory(): Path? = System.getProperty(DIR_PROPERTY)?.let { Paths.get(it).resolve("goal-met") }

    /** Writes [cases] as `cases.json` under [directory]. */
    fun write(directory: Path, cases: List<DecisionCase>) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("cases.json"), encode(cases))
    }
}
