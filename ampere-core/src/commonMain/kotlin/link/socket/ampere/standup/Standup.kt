package link.socket.ampere.standup

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.status.MeetingStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.MeetingTask
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.meetings.Meeting
import link.socket.ampere.agents.events.meetings.MeetingInvitation
import link.socket.ampere.agents.events.meetings.MeetingMessagingDetails
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.meetings.MeetingType
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.probe.Probe
import link.socket.ampere.probe.ProbeReport
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.room.Author
import link.socket.ampere.room.ReviewGate
import link.socket.ampere.room.RoomCard
import link.socket.ampere.room.RoomId
import link.socket.ampere.room.RoomService
import link.socket.ampere.room.ThreadSubject
import link.socket.ampere.room.roomThreadId
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.roster.calibration.EstimateCalibrationSource
import link.socket.ampere.roster.calibration.Estimator
import link.socket.ampere.roster.calibration.WorkEstimate
import link.socket.ampere.util.randomUUID

/**
 * The weekly standup: a Meeting over the Blueprint event stream (AMPR-379).
 *
 * Converges the Planner, Estimator, Scheduler and Coordinator — with the Inspector
 * reviewing the Planner's revision — on everything that happened since the last
 * standup, and leaves three things behind: a revision proposal, one status post in
 * the Room's general thread, and the list of what the human has to settle.
 *
 * It is a `Meeting` in Ampere's sense: `MeetingEvent.MeetingStarted` and
 * `MeetingEvent.MeetingCompleted` (the concluded event, with primitives-only
 * `MeetingOutcome`s) go through the door, and the Meeting row is written when a
 * [MeetingRepository] is supplied. It does not go through `MeetingOrchestrator`,
 * whose `scheduleMeeting` requires a future time against the wall clock and so
 * cannot run a meeting now; the schedule itself lives in the consumer's lifecycle
 * row, which calls [run] when the week is up.
 *
 * Watt discipline: every step but the narrative is deterministic and 0W. The
 * narrative is [narrator]'s, and defaults to the template.
 */
class Standup(
    private val room: RoomService,
    private val reviewGate: ReviewGate,
    private val eventApi: AgentEventApi,
    private val meetingRepository: MeetingRepository? = null,
    private val narrator: StandupNarrator = TemplatedNarrator,
    private val sequenceProbe: Probe<CanonWorkGraph> = SequenceProbe(),
    private val clock: Clock = Clock.System,
    private val idGenerator: () -> String = { randomUUID() },
) {

    /**
     * Run the standup for [graph]'s Room.
     *
     * @param since the previous standup; only events at or after it are read.
     * @param calibration what the Estimator's Recall step reads.
     * @param baseline the current estimate per item; items absent from it are
     *   named in the proposal's rationale and left unscheduled.
     * @param availability the windows the Scheduler may propose sessions in.
     * @param events the stream since [since]; read from the store through
     *   [eventApi] when null.
     * @param revision the number the proposal carries.
     * @param runId the Arc run, stamped onto every event the standup publishes.
     */
    suspend fun run(
        graph: CanonWorkGraph,
        since: Instant,
        calibration: EstimateCalibrationSource,
        baseline: List<WorkEstimate>,
        availability: List<AvailabilityWindow> = emptyList(),
        events: List<Event>? = null,
        revision: Int = 1,
        runId: RunId? = null,
    ): Result<StandupOutcome> {
        val roomId = RoomId.forProject(graph.project.canonId)
        val generalThreadId = roomThreadId(roomId, ThreadSubject.General)
        val threads = room.threads(roomId).getOrElse { return roomWrite("threads", it) }
        if (threads.none { it.threadId == generalThreadId }) {
            return standupFailure(StandupFailure.RoomNotOpen(roomId))
        }

        val now = clock.now()
        val meetingId = idGenerator()
        val coordinator = Author.Role(BlueprintRoster.coordinator.id)
        val planner = Author.Role(BlueprintRoster.planner.id)
        val inspector = Author.Role(BlueprintRoster.inspector.id)

        // Perceive: the stream since the last standup.
        val stream = events ?: eventApi.getRecentEvents(since)
        val digest = StandupDeliberation.digest(stream, since)

        meetingRepository?.saveMeeting(meeting(meetingId, roomId, generalThreadId, since, now))
            ?.onFailure { return roomWrite("saveMeeting", it) }
        val started = MeetingEvent.MeetingStarted(
            eventId = idGenerator(),
            meetingId = meetingId,
            threadId = generalThreadId,
            eventSource = coordinator.eventSource(),
            timestamp = now,
        )
        eventApi.publish(started, runId = runId).onFailure { return roomWrite("publish MeetingStarted", it) }

        // Planner: what is left, in dependency order.
        val remaining = StandupDeliberation.order(StandupDeliberation.remaining(graph, digest))
        val remainingIds = remaining.map { it.canonId }

        // Estimator: the Recall step reads the calibration source.
        val estimates = Estimator.calibrate(baseline.filter { it.itemId in remainingIds }, calibration)
        val rationale = mutableListOf<String>()
        estimates.map { it.calibration }.distinct().forEach { calibrationValue ->
            if (calibrationValue.samples == 0) {
                rationale += "calibration ×${calibrationValue.multiplier} is not earned: 0 samples"
            } else {
                rationale += "calibration ×${calibrationValue.multiplier} from ${calibrationValue.samples} session(s)"
            }
        }
        remainingIds
            .filter { id -> estimates.none { it.itemId == id } }
            .forEach { rationale += "no estimate for ${it.value}" }

        // Scheduler: sessions against availability.
        val schedule = StandupDeliberation.schedule(estimates, remainingIds, availability, now)
        schedule.unscheduled.forEach { rationale += "not enough availability for ${it.value}" }

        val proposal = RevisionProposal(
            revision = revision,
            remaining = remainingIds,
            estimates = estimates,
            sessions = schedule.sessions,
            remainingWork = schedule.remainingWork,
            projectedFinish = schedule.projectedFinish,
            rationale = rationale,
        )

        // Inspector reviews the Planner's revision before it reaches the Room as a card.
        val card = RoomCard.PlanRevision(
            revision = revision,
            remainingItems = remainingIds.size,
            remaining = schedule.remainingWork,
            projectedFinish = schedule.projectedFinish,
            changes = rationale,
        )
        val submission = reviewGate.submit(
            roomId = roomId,
            threadId = generalThreadId,
            author = planner,
            body = "Revision $revision: ${remainingIds.size} item(s) remaining, finish " +
                (schedule.projectedFinish?.toString() ?: "unscheduled"),
            card = card,
            causedBy = started.eventId,
        ).getOrElse { return roomWrite("submit revision", it) }

        val released = when (submission) {
            is ReviewGate.Submission.Released -> true
            is ReviewGate.Submission.Pending -> {
                val report = ProbeReport(
                    probeId = sequenceProbe.id,
                    subjectId = graph.project.canonId.value,
                    verdict = sequenceProbe.evaluate(graph),
                )
                reviewGate.review(submission.reviewId, inspector, report, causedBy = started.eventId)
                    .getOrElse { return standupFailure(StandupFailure.ReviewFailed(it.message ?: "review"), it) }
                    .let { it is ReviewGate.ReviewOutcome.Released }
            }
        }

        // Coordinator: escalations, then the one metered step, then the status post.
        val escalations = buildList {
            digest.blocked.forEach { add(StandupEscalation(it.taskId, "blocked by ${it.blockedBy}: ${it.reason}")) }
            digest.openVerdicts.forEach { verdict ->
                add(
                    StandupEscalation(
                        subjectId = verdict.subjectId,
                        reason = "${verdict.kind} verdict from ${verdict.probeId.value}: ${verdict.reason}",
                        threadId = threads.firstOrNull { thread ->
                            (thread.subject as? ThreadSubject.Verdict)?.subjectId == verdict.subjectId
                        }?.threadId,
                    ),
                )
            }
            if (!released) {
                add(
                    StandupEscalation(graph.project.canonId.value, "revision $revision withheld by the Inspector"),
                )
            }
        }

        val brief = StandupBrief(
            roomId = roomId,
            projectName = graph.project.name,
            since = since,
            now = now,
            eventsSince = digest.eventCount,
            completed = digest.completed.size,
            started = digest.started.size,
            remaining = remainingIds.size,
            blocked = digest.blocked.map { "${it.taskId} (${it.reason})" },
            openVerdicts = digest.openVerdicts.map { "${it.subjectId}: ${it.kind}" },
            proposal = proposal,
            revisionReleased = released,
        )
        val narrative = narrator.narrate(brief).getOrElse { TemplatedNarrator.render(brief) }

        val statusPost = room.post(
            threadId = generalThreadId,
            author = coordinator,
            body = narrative,
            card = RoomCard.Status(
                headline = "Standup: ${digest.completed.size} done, ${remainingIds.size} remaining",
                completed = digest.completed.size,
                remaining = remainingIds.size,
                blocked = escalations.size,
                projectedFinish = schedule.projectedFinish,
            ),
            causedBy = started.eventId,
        ).getOrElse { return roomWrite("post status", it) }

        val outcomes = buildList<MeetingOutcome> {
            add(
                MeetingOutcome.DecisionMade(
                    id = idGenerator(),
                    description = "Revision $revision ${if (released) "released" else "withheld"}: " +
                        "${remainingIds.size} item(s) remaining, finish ${schedule.projectedFinish ?: "unscheduled"}",
                    decidedBy = planner.eventSource(),
                ),
            )
            escalations.forEach { escalation ->
                add(
                    MeetingOutcome.BlockerRaised(
                        id = idGenerator(),
                        description = "${escalation.subjectId}: ${escalation.reason}",
                        raisedBy = coordinator.eventSource(),
                        assignedTo = AssignedTo.Human,
                    ),
                )
            }
        }

        val completedAt = clock.now()
        meetingRepository?.updateMeetingStatus(
            meetingId,
            MeetingStatus.Completed(
                completedAt = completedAt,
                attendedBy = participants().map { EventSource.Agent(it) },
                messagingDetails = MeetingMessagingDetails(roomId.value, generalThreadId),
                outcomes = outcomes,
            ),
        )?.onFailure { return roomWrite("updateMeetingStatus", it) }

        eventApi.publish(
            MeetingEvent.MeetingCompleted(
                eventId = idGenerator(),
                meetingId = meetingId,
                outcomes = outcomes,
                eventSource = coordinator.eventSource(),
                timestamp = completedAt,
            ),
            causedBy = started.eventId,
            runId = runId,
        ).onFailure { return roomWrite("publish MeetingCompleted", it) }

        return Result.success(
            StandupOutcome(
                meetingId = meetingId,
                revisionProposal = proposal,
                revisionReleased = released,
                statusPost = statusPost,
                narrative = narrative,
                escalations = escalations,
            ),
        )
    }

    private fun participants(): List<String> = listOf(
        BlueprintRoster.planner,
        BlueprintRoster.estimator,
        BlueprintRoster.scheduler,
        BlueprintRoster.inspector,
        BlueprintRoster.coordinator,
    ).map { it.id.value }

    private fun meeting(
        meetingId: String,
        roomId: RoomId,
        generalThreadId: String,
        since: Instant,
        now: Instant,
    ): Meeting = Meeting(
        id = meetingId,
        type = MeetingType.Standup(teamId = roomId.value, sprintId = "since-${since.toEpochMilliseconds()}"),
        status = MeetingStatus.InProgress(
            startedAt = now,
            messagingDetails = MeetingMessagingDetails(roomId.value, generalThreadId),
        ),
        invitation = MeetingInvitation(
            title = "Weekly standup",
            agenda = listOf(
                MeetingTask.AgendaItem(id = "$meetingId/perceive", title = "What happened since $since"),
                MeetingTask.AgendaItem(id = "$meetingId/estimate", title = "Calibrated estimates"),
                MeetingTask.AgendaItem(id = "$meetingId/schedule", title = "Sessions and projected finish"),
                MeetingTask.AgendaItem(id = "$meetingId/escalate", title = "What the human must settle"),
            ),
            requiredParticipants = participants().map { AssignedTo.Agent(it) },
        ),
        messagingDetails = MeetingMessagingDetails(roomId.value, generalThreadId),
    )

    private fun <T> roomWrite(step: String, cause: Throwable): Result<T> =
        standupFailure(StandupFailure.RoomWrite(step, cause.message ?: cause::class.simpleName.orEmpty()), cause)
}

/** Whether [event] is one of the kinds a standup reads. Exposed for fixtures that want to count them. */
fun Event.isStandupInput(): Boolean = when (this) {
    is TaskEvent,
    is ProbeEvent,
    is MessageEvent.EscalationRequested,
    is RoomEvent.ThreadResolved,
    -> true
    else -> false
}
