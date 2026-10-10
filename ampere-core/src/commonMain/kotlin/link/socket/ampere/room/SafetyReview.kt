package link.socket.ampere.room

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.probe.Verdict
import link.socket.ampere.probe.safety.HazardFinding
import link.socket.ampere.probe.safety.HazardSubject
import link.socket.ampere.probe.safety.MitigationPlan
import link.socket.ampere.probe.safety.SafetyInspection
import link.socket.ampere.probe.safety.SafetyProbe
import link.socket.ampere.probe.safety.WorkPlanSubject
import link.socket.ampere.roster.Roster

/**
 * The Inspector's hazard rule (AMPR-380): run `SafetyProbe` over a plan, open one
 * Room thread per finding, and insert one mitigation Task per finding before the
 * hazardous Task.
 *
 * This is the half of the team layer that does not fit
 * [VerdictThreadBinding]. That binding reacts to `ProbeEvent.VerdictReached` on
 * the bus, and a verdict event carries primitives plus a `Verdict` by design — so
 * the findings are not on it, and could not be without binding the event to this
 * one Probe's vocabulary. The rule therefore reads the findings from the Probe
 * directly, which is sound because the classifier is deterministic and a verdict
 * is recomputed rather than replayed.
 *
 * Who does what matches the roster: the verifier (`Roster.verifier`, the
 * Inspector) posts the hazard cards, and the thread is assigned to the role the
 * roster names as able to resolve this Probe's verdicts
 * (`Roster.resolverFor`) — the Planner, because the remedy is a change to the
 * graph.
 *
 * **A roster that names neither still gets its plan mitigated** (AMPR-409). The
 * mitigation Tasks are graph work and go in regardless; what a seatless roster lacks
 * is somebody to post the card and somebody to own the thread, so no hazard thread
 * opens and the outcome carries no threads.
 *
 * **Publishing is the caller's.** This does not publish a
 * `ProbeEvent.VerdictReached`; the Probe belongs in the host's `ProbeSuite`, which
 * publishes every verdict in one place. Running the Probe twice — once for the
 * trace, once here — costs nothing because it is 0W, and the alternative is two
 * verdict events for one plan edit.
 *
 * **Quiet on re-review.** Thread ids are derived from the finding, and
 * [MitigationPlan] is idempotent, so reviewing an already-reviewed plan opens no
 * thread, posts no card, and returns the graph unchanged. A plan edit that
 * introduces a new hazard produces exactly one new thread.
 */
class SafetyReview(
    private val room: RoomService,
    private val roomId: RoomId,
    private val roster: Roster,
    private val probe: SafetyProbe,
    private val clock: Clock = Clock.System,
) {

    /**
     * Inspect [subject], mitigate what it carries, and say so in the Room.
     *
     * Returns the *mitigated* plan: the caller decides whether to adopt it, the
     * way it decides what to do with any Probe verdict (Socket decision D21).
     */
    suspend fun review(subject: WorkPlanSubject, causedBy: EventId? = null): Result<SafetyReviewOutcome> {
        val inspection = probe.inspect(subject)
        val insertion = MitigationPlan.insert(subject.graph, inspection.findings, clock.now())
        val mitigationFor = insertion.inserted.associateBy { it.finding }

        val verifierId = roster.verifier
        val assignee = roster.resolverFor(probe.id)
        if (verifierId == null || assignee == null) {
            return Result.success(
                SafetyReviewOutcome(
                    verdict = inspection.verdict,
                    inspection = inspection,
                    graph = insertion.graph,
                    inserted = insertion.inserted,
                ),
            )
        }

        val existing = room.threads(roomId)
            .getOrElse { return Result.failure(it) }
            .mapNotNull { it.subject as? ThreadSubject.Hazard }
            .mapTo(mutableSetOf()) { it.key }

        val verifier = Author.Role(verifierId)
        val threads = mutableListOf<MessageThreadId>()
        val opened = mutableListOf<HazardFinding>()

        inspection.findings.forEach { finding ->
            val threadSubject = ThreadSubject.Hazard(finding.subject.value, finding.category)
            val threadId = roomThreadId(roomId, threadSubject)
            threads += threadId
            if (!existing.add(threadSubject.key)) return@forEach

            room.thread(
                roomId = roomId,
                subject = threadSubject,
                assignedTo = assignee,
                title = "Hazard: ${finding.category.name} on ${finding.subject.value}",
                causedBy = causedBy,
            ).getOrElse { return Result.failure(it) }

            val mitigation = mitigationFor[finding]
            room.post(
                threadId = threadId,
                author = verifier,
                body = body(finding, mitigation),
                card = RoomCard.Hazard(
                    category = finding.category,
                    subjectId = finding.subject.value,
                    mitigationHint = finding.mitigationHint,
                    evidence = finding.evidence,
                    mitigationTaskId = mitigation?.task?.canonId,
                ),
                causedBy = causedBy,
            ).getOrElse { return Result.failure(it) }

            opened += finding
        }

        return Result.success(
            SafetyReviewOutcome(
                verdict = inspection.verdict,
                inspection = inspection,
                graph = insertion.graph,
                inserted = insertion.inserted,
                threads = threads,
                openedThreads = opened,
            ),
        )
    }

    private fun body(finding: HazardFinding, mitigation: MitigationPlan.InsertedMitigation?): String = buildString {
        append("${finding.category.name} on ${finding.subject.value}: ${finding.evidence}.")
        when {
            mitigation != null -> append(
                " Mitigation inserted before it: \"${mitigation.task.title}\" (${mitigation.task.canonId.value}).",
            )

            finding.subject is HazardSubject.Line -> append(
                " No task in the plan uses this line, so there is no step to put a mitigation before — " +
                    "say which step it belongs to and the mitigation follows.",
            )

            else -> append(" No mitigation task was inserted: the subject is not an item of this plan.")
        }
    }
}

/**
 * What one [SafetyReview.review] did.
 *
 * @property verdict The Probe's verdict on the plan as it was handed in — `Warn`
 *   for a plan carrying hazards, `Undetermined` when a Task could not be read.
 * @property inspection Every finding and every unreadable subject behind [verdict].
 * @property graph The plan with the mitigation Tasks in it. Equal to the input
 *   graph when nothing was inserted.
 * @property inserted The mitigation Tasks, each with the finding that asked for it.
 * @property threads The Room thread for every finding, whether this review opened
 *   it or found it already open. Empty when the roster has no seat to hold the
 *   conversation.
 * @property openedThreads The findings this review opened a thread for — empty on
 *   a re-review of an unchanged plan.
 */
data class SafetyReviewOutcome(
    val verdict: Verdict,
    val inspection: SafetyInspection,
    val graph: CanonWorkGraph,
    val inserted: List<MitigationPlan.InsertedMitigation> = emptyList(),
    val threads: List<MessageThreadId> = emptyList(),
    val openedThreads: List<HazardFinding> = emptyList(),
)
