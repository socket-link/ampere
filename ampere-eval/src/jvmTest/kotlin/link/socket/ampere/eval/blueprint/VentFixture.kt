package link.socket.ampere.eval.blueprint

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonMilestone
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent
import link.socket.ampere.link.LinkId
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.probe.safety.HazardCategory
import link.socket.ampere.probe.safety.HazardFinding
import link.socket.ampere.probe.safety.HazardSubject
import link.socket.ampere.probe.safety.MitigationHint
import link.socket.ampere.probe.safety.MitigationPlan
import link.socket.ampere.probe.safety.PlanLine
import link.socket.ampere.probe.safety.SafetyProbe
import link.socket.ampere.probe.safety.WorkPlan
import link.socket.ampere.room.RoomId
import link.socket.ampere.room.ThreadSubject
import link.socket.ampere.room.VerdictKind
import link.socket.ampere.room.roomThreadId
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.roster.calibration.EstimateCategory
import link.socket.ampere.roster.calibration.WorkEstimate
import link.socket.ampere.standup.AvailabilityWindow
import link.socket.ampere.version.AMPERE_VERSION

/**
 * The vent fixture (AMPR-379 task 5; socket#1406 Phase 3): a bathroom exhaust vent
 * as one Blueprint `Project` with four milestones, and the recorded event stream of
 * its first week — three `Violated` verdicts that the assigned roles resolve in one
 * pass, one `Undetermined` verdict (the grille publishes no CFM) that nobody can,
 * and six lifecycle events for the standup to read.
 *
 * Blueprint vocabulary lives here and in the role prompts, by design (D9). Nothing
 * in `ampere-core` names a duct.
 *
 * The stream is a [Trace] — the eval suite's own fixture format — so the replay
 * decodes every payload the way a recorded run would be decoded, and
 * `PlaybackRelay` validates it like any other trace.
 */
object VentFixture {

    val since: Instant = Instant.parse("2026-09-21T09:00:00Z")
    val now: Instant = Instant.parse("2026-09-28T09:00:00Z")

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("reminders"),
            sourceSystem = "reminders",
            nativeId = "vent-42",
            etag = null,
        ),
        observedAt = since,
        nativePayload = NativePayload(schema = NativeSchema("Reminder"), fields = JsonObject(emptyMap())),
    )

    val project =
        CanonProject(
            CanonId("vent-42"),
            provenance,
            name = "Bathroom exhaust vent",
            status = CanonWorkStatus.IN_PROGRESS,
        )
    val roomId: RoomId = RoomId.forProject(project.canonId)

    val partFit = ProbeId("blueprint.part-fit")
    val sequence = ProbeId(SequenceProbe.ID)

    private fun milestone(id: String, name: String) =
        CanonMilestone(CanonId(id), provenance, name = name, projectId = project.canonId)

    private fun item(
        id: String,
        title: String,
        status: CanonWorkStatus = CanonWorkStatus.TODO,
        vararg dependsOn: String,
    ) = CanonWorkItem(
        CanonId(id),
        provenance,
        title = title,
        status = status,
        projectId = project.canonId,
        dependsOn = dependsOn.map(::CanonId),
    )

    /**
     * The week's work, titled the way a Blueprint would phrase it for a person —
     * which is also what makes the hazards readable (AMPR-380). Cutting the duct,
     * the mains connection and the resin sealant are all really in this build; the
     * titles do not dodge them, and `SafetyProbe` finds exactly three.
     */
    val graph: CanonWorkGraph = CanonWorkGraph(
        project = project,
        milestones = listOf(
            milestone("ms-measure", "Measure and spec"),
            milestone("ms-source", "Source parts"),
            milestone("ms-build", "Fabricate and assemble"),
            milestone("ms-verify", "Install and verify"),
        ),
        items = listOf(
            item("measure-opening", "Measure the opening and the duct run", CanonWorkStatus.DONE),
            item("pick-grille", "Choose a grille"),
            item("pick-fan", "Choose an exhaust fan"),
            item("pick-duct", "Choose the duct"),
            item(
                "order-parts",
                "Order the parts",
                CanonWorkStatus.TODO,
                "pick-grille",
                "pick-fan",
                "pick-duct",
            ),
            item("cut-duct", "Cut the duct to length", CanonWorkStatus.TODO, "order-parts"),
            item(
                "mount-fan",
                "Mount the fan and wire it to the switched mains circuit",
                CanonWorkStatus.TODO,
                "cut-duct",
            ),
            item("fit-grille", "Fit the grille", CanonWorkStatus.TODO, "mount-fan"),
            item("verify-airflow", "Verify airflow at the grille", CanonWorkStatus.TODO, "fit-grille"),
        ),
    )

    /**
     * The manifest, as the hazard check sees it: four lines, two of which say what
     * they are in their interface kind. Socket's own `ManifestLine` implements
     * `LineRef`; [PlanLine] is what a fixture with no manifest type uses.
     */
    val lines: List<PlanLine> = listOf(
        PlanLine(
            lineId = "line-duct",
            kind = "RIGID_DUCT",
            label = "6in rigid duct",
            appliesTo = setOf(CanonId("cut-duct"), CanonId("mount-fan")),
        ),
        PlanLine(
            lineId = "line-fan",
            kind = "MAINS_VOLTAGE",
            label = "Inline exhaust fan hardwired to the lighting circuit",
            appliesTo = setOf(CanonId("mount-fan")),
        ),
        PlanLine(
            lineId = "line-sealant",
            kind = "RESIN",
            label = "Two-part resin duct sealant",
            appliesTo = setOf(CanonId("mount-fan")),
        ),
        PlanLine(
            lineId = "line-grille",
            kind = "GRILLE",
            label = "Ceiling grille",
            appliesTo = setOf(CanonId("fit-grille")),
        ),
    )

    val plan: WorkPlan = WorkPlan(graph, lines)

    /** The hazards this build carries. Pinned: a replay that finds fewer is a failing replay. */
    val expectedFindings: List<HazardFinding> = listOf(
        HazardFinding(
            category = HazardCategory.CUTTING_OR_POWER_TOOLS,
            subject = HazardSubject.Task(CanonId("cut-duct")),
            evidence = "task text matched \"cut\"",
            mitigationHint = MitigationHint.USE_PPE,
        ),
        HazardFinding(
            category = HazardCategory.ELECTRICAL,
            subject = HazardSubject.Task(CanonId("mount-fan")),
            evidence = "manifest line line-fan has kind MAINS_VOLTAGE",
            mitigationHint = MitigationHint.CHECK_LOCAL_CODE,
        ),
        HazardFinding(
            category = HazardCategory.FUMES_OR_CHEMICALS,
            subject = HazardSubject.Task(CanonId("mount-fan")),
            evidence = "manifest line line-sealant has kind RESIN",
            mitigationHint = MitigationHint.CONFIRM_VENTILATION,
        ),
    )

    /** The `Warn` reason the Probe must reach over [plan]. */
    val expectedSafetyReason: String =
        "3 hazard(s): CUTTING_OR_POWER_TOOLS on cut-duct, ELECTRICAL on mount-fan, " +
            "FUMES_OR_CHEMICALS on mount-fan"

    /** The mitigation Tasks the Inspector rule inserts, in insertion order. */
    val expectedMitigations: List<CanonId> = expectedFindings.map { finding ->
        MitigationPlan.mitigationId(
            CanonId(finding.subject.value),
            finding.mitigationHint,
        )
    }

    val safety: ProbeId = ProbeId(SafetyProbe.ID)

    /**
     * The Estimator's own numbers, including one for each mitigation Task the
     * safety rule inserts (AMPR-380) — a mitigation is real work, so a standup that
     * could not estimate it would re-plan around a hole.
     */
    val baseline: List<WorkEstimate> = listOf(
        WorkEstimate(expectedMitigations[0], EstimateCategory.ADMIN, 10.minutes),
        WorkEstimate(expectedMitigations[1], EstimateCategory.RESEARCH, 30.minutes),
        WorkEstimate(expectedMitigations[2], EstimateCategory.PHYSICAL_WORK, 20.minutes),
        WorkEstimate(CanonId("pick-grille"), EstimateCategory.RESEARCH, 45.minutes),
        WorkEstimate(CanonId("pick-fan"), EstimateCategory.RESEARCH, 45.minutes),
        WorkEstimate(CanonId("pick-duct"), EstimateCategory.RESEARCH, 30.minutes),
        WorkEstimate(CanonId("order-parts"), EstimateCategory.ADMIN, 30.minutes),
        WorkEstimate(CanonId("cut-duct"), EstimateCategory.PHYSICAL_WORK, 2.hours),
        WorkEstimate(CanonId("mount-fan"), EstimateCategory.ASSEMBLY, 90.minutes),
        WorkEstimate(CanonId("fit-grille"), EstimateCategory.ASSEMBLY, 1.hours),
        WorkEstimate(CanonId("verify-airflow"), EstimateCategory.PHYSICAL_WORK, 30.minutes),
    )

    /**
     * Three Saturday afternoons. The third one is here because the safety rule
     * inserts mitigation Tasks (AMPR-380) and they are real work: two windows held
     * the build before the hazards were checked and no longer do, which is the
     * honest consequence of planning the safe version of the same build.
     */
    val availability: List<AvailabilityWindow> = listOf(
        AvailabilityWindow(start = Instant.parse("2026-10-03T13:00:00Z"), end = Instant.parse("2026-10-03T17:00:00Z")),
        AvailabilityWindow(start = Instant.parse("2026-10-10T13:00:00Z"), end = Instant.parse("2026-10-10T17:00:00Z")),
        AvailabilityWindow(start = Instant.parse("2026-10-17T13:00:00Z"), end = Instant.parse("2026-10-17T17:00:00Z")),
    )

    /** Subject ids the Room's verdict binding treats as its own. */
    val subjectIds: Set<String> = setOf("vent-42", "duct", "fan", "grille") + graph.items.map { it.canonId.value }

    private val inspector = EventSource.Agent(BlueprintRoster.inspector.id.value)
    private val lifecycle = EventSource.Agent("blueprint-lifecycle")

    private var counter = 0
    private fun id(prefix: String) = "$prefix-${++counter}"

    private fun verdict(
        at: Instant,
        subjectId: String,
        verdict: ProbeVerdict,
        probeId: ProbeId,
    ) = ProbeEvent.VerdictReached(
        eventId = id("verdict"),
        eventSource = inspector,
        timestamp = at,
        probeId = probeId,
        subjectId = subjectId,
        verdict = verdict,
    )

    /** A role's recorded post in a verdict thread — the one pass the policy allows before the human is asked. */
    private fun pass(
        at: Instant,
        role: String,
        subject: ThreadSubject.Verdict,
        body: String,
    ): MessageEvent.MessagePosted {
        val threadId = roomThreadId(roomId, subject)
        return MessageEvent.MessagePosted(
            eventId = id("post"),
            threadId = threadId,
            channel = roomId.channel(),
            message = Message(
                id = id("msg"),
                threadId = threadId,
                sender = MessageSender.Agent(role),
                content = body,
                timestamp = at,
            ),
        )
    }

    private fun started(at: Instant, taskId: String) =
        TaskEvent.TaskStarted(
            eventId = id("started"),
            taskId = taskId,
            eventSource = lifecycle,
            timestamp = at,
            assignedTo = "human",
        )

    private fun completed(at: Instant, taskId: String) =
        TaskEvent.TaskCompleted(
            eventId = id("completed"),
            taskId = taskId,
            eventSource = lifecycle,
            timestamp = at,
            summary = "$taskId done",
        )

    /** The recorded week, in order. */
    fun events(): List<Event> {
        counter = 0
        val t = since
        val duct = ThreadSubject.Verdict("duct", VerdictKind.VIOLATED)
        val fan = ThreadSubject.Verdict("fan", VerdictKind.VIOLATED)
        val plan = ThreadSubject.Verdict("vent-42", VerdictKind.VIOLATED)
        return listOf(
            // Day 1: the Inspector runs the suite over the plan and its parts.
            verdict(t + 1.hours, "duct", ProbeVerdict.Violated("duct undersized: 4in run for a 226 CFM fan"), partFit),
            verdict(
                t + 1.hours,
                "fan",
                ProbeVerdict.Violated("fan lead time 3 weeks exceeds the Source parts milestone"),
                partFit,
            ),
            verdict(
                t + 1.hours,
                "vent-42",
                ProbeVerdict.Violated("dangling dependsOn: fit-grille -> mount-grille"),
                sequence,
            ),
            verdict(
                t + 1.hours,
                "grille",
                ProbeVerdict.Undetermined("grille publishes no CFM", UndeterminedCause.EVIDENCE_ABSENT),
                partFit,
            ),
            // Day 2: one pass each by the assigned role, then the Probe re-runs.
            pass(
                t + 25.hours,
                "scout",
                duct,
                "Swapped to 6in rigid duct: 226 CFM at 0.1 in. wg on the fan's curve. " +
                    "Source: manufacturer spec sheet, read today.",
            ),
            verdict(t + 26.hours, "duct", ProbeVerdict.Holds("6in duct carries 226 CFM"), partFit),
            pass(
                t + 27.hours,
                "scout",
                fan,
                "Same fan at a regional distributor, 4-day lead time. Source: distributor listing, read today.",
            ),
            verdict(t + 28.hours, "fan", ProbeVerdict.Holds("4-day lead time fits the milestone"), partFit),
            pass(
                t + 29.hours,
                "planner",
                plan,
                "Replaced the dangling mount-grille step with mount-fan; fit-grille now depends on mount-fan.",
            ),
            verdict(t + 30.hours, "vent-42", ProbeVerdict.Holds(), sequence),
            // Days 3-5: six lifecycle events the standup reads. The grille is never resolved.
            started(t + 49.hours, "pick-duct"),
            completed(t + 50.hours, "pick-duct"),
            started(t + 73.hours, "pick-fan"),
            completed(t + 74.hours, "pick-fan"),
            started(t + 97.hours, "order-parts"),
            completed(t + 98.hours, "order-parts"),
        )
    }

    /** The week as a recorded [Trace], the way the eval suite keeps its fixtures. */
    fun trace(): Trace {
        val events = events()
        return Trace(
            id = "trace-vent-42",
            runId = "run-vent-42",
            arcId = "blueprint",
            createdAt = now.toEpochMilliseconds(),
            events = events.mapIndexed { index, event ->
                TraceEvent(
                    index = index,
                    timestamp = event.timestamp.toEpochMilliseconds(),
                    type = event.eventType,
                    payload = DEFAULT_JSON.encodeToJsonElement(Event.serializer(), event),
                )
            },
            producerVersion = AMPERE_VERSION,
        )
    }
}
