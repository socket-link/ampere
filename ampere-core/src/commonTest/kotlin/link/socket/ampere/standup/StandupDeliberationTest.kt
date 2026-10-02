package link.socket.ampere.standup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.room.RoomId
import link.socket.ampere.roster.calibration.CalibratedEstimate
import link.socket.ampere.roster.calibration.Calibration
import link.socket.ampere.roster.calibration.EstimateCalibrationSource
import link.socket.ampere.roster.calibration.EstimateCategory
import link.socket.ampere.roster.calibration.Estimator
import link.socket.ampere.roster.calibration.NoCalibration
import link.socket.ampere.roster.calibration.WorkEstimate

/** AMPR-379 task 4, the pure half: digest, ordering, scheduling, and the templated narrative. */
class StandupDeliberationTest {

    private val since = Instant.parse("2026-09-21T09:00:00Z")
    private val now = Instant.parse("2026-09-28T09:00:00Z")
    private val source = EventSource.Agent("lifecycle")

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("link-1"),
            sourceSystem = "reminders",
            nativeId = "n-1",
            etag = null,
        ),
        observedAt = since,
        nativePayload = NativePayload(schema = NativeSchema("Reminder"), fields = JsonObject(emptyMap())),
    )

    private val project =
        CanonProject(CanonId("vent-42"), provenance, name = "Bathroom vent", status = CanonWorkStatus.IN_PROGRESS)

    private fun item(
        id: String,
        status: CanonWorkStatus = CanonWorkStatus.TODO,
        vararg dependsOn: String,
    ) = CanonWorkItem(
        CanonId(id),
        provenance,
        title = id,
        status = status,
        projectId = project.canonId,
        dependsOn = dependsOn.map(::CanonId),
    )

    private val graph = CanonWorkGraph(
        project = project,
        items = listOf(
            item("measure", CanonWorkStatus.DONE),
            item("pick-duct"),
            item("order-parts", CanonWorkStatus.TODO, "pick-duct"),
            item("cut-duct", CanonWorkStatus.TODO, "order-parts"),
            item("fit-grille", CanonWorkStatus.TODO, "cut-duct"),
        ),
    )

    private fun started(taskId: String, at: Instant) =
        TaskEvent.TaskStarted(
            eventId = "s-$taskId",
            taskId = taskId,
            eventSource = source,
            timestamp = at,
            assignedTo = "human",
        )

    private fun completed(taskId: String, at: Instant) =
        TaskEvent.TaskCompleted(
            eventId = "c-$taskId",
            taskId = taskId,
            eventSource = source,
            timestamp = at,
            summary = "done",
        )

    private fun sixLifecycleEvents() = listOf(
        started("pick-duct", since + 1.hours),
        completed("pick-duct", since + 2.hours),
        started("order-parts", since + 3.hours),
        completed("order-parts", since + 4.hours),
        started("cut-duct", since + 5.hours),
        completed("cut-duct", since + 6.hours),
    )

    @Test
    fun `six lifecycle events digest into three completions and nothing blocked`() {
        val digest = StandupDeliberation.digest(sixLifecycleEvents(), since)

        assertEquals(6, digest.eventCount)
        assertEquals(setOf("pick-duct", "order-parts", "cut-duct"), digest.completed)
        assertEquals(setOf("pick-duct", "order-parts", "cut-duct"), digest.started)
        assertTrue(digest.blocked.isEmpty())
        assertTrue(digest.openVerdicts.isEmpty())
    }

    @Test
    fun `events before since are ignored`() {
        val digest = StandupDeliberation.digest(listOf(completed("measure", since - 1.hours)), since)

        assertEquals(0, digest.eventCount)
        assertTrue(digest.completed.isEmpty())
    }

    @Test
    fun `a verdict stays open until the same probe holds`() {
        val probe = ProbeId("blueprint.part-fit")
        fun verdict(id: String, at: Instant, verdict: ProbeVerdict) = ProbeEvent.VerdictReached(
            eventId = id,
            eventSource = source,
            timestamp = at,
            probeId = probe,
            subjectId = "grille",
            verdict = verdict,
        )

        val stillOpen = StandupDeliberation.digest(
            listOf(
                verdict("v1", since + 1.hours, ProbeVerdict.Undetermined("no CFM", UndeterminedCause.EVIDENCE_ABSENT)),
            ),
            since,
        )
        val closed = StandupDeliberation.digest(
            listOf(
                verdict("v1", since + 1.hours, ProbeVerdict.Violated("undersized")),
                verdict("v2", since + 2.hours, ProbeVerdict.Holds()),
            ),
            since,
        )

        assertEquals(listOf("grille"), stillOpen.openVerdicts.map { it.subjectId })
        assertEquals("undetermined", stillOpen.openVerdicts.single().kind)
        assertTrue(closed.openVerdicts.isEmpty())
    }

    @Test
    fun `remaining excludes done items and completions since the last standup`() {
        val digest = StandupDeliberation.digest(sixLifecycleEvents(), since)

        val remaining = StandupDeliberation.remaining(graph, digest)

        assertEquals(listOf("fit-grille"), remaining.map { it.canonId.value })
    }

    @Test
    fun `order places every item after its dependencies and keeps ties stable`() {
        val shuffled = listOf(
            item("fit-grille", CanonWorkStatus.TODO, "cut-duct"),
            item("cut-duct", CanonWorkStatus.TODO, "order-parts"),
            item("pick-duct"),
            item("order-parts", CanonWorkStatus.TODO, "pick-duct"),
            item("buy-screws"),
        )

        val ordered = StandupDeliberation.order(shuffled).map { it.canonId.value }

        // The first ready item in graph order is placed next, so a late independent item waits its turn.
        assertEquals(listOf("pick-duct", "order-parts", "cut-duct", "fit-grille", "buy-screws"), ordered)
    }

    @Test
    fun `a cycle is still placed rather than dropped`() {
        val cyclic = listOf(item("a", CanonWorkStatus.TODO, "b"), item("b", CanonWorkStatus.TODO, "a"), item("c"))

        val ordered = StandupDeliberation.order(cyclic).map { it.canonId.value }

        assertEquals(listOf("c", "a", "b"), ordered)
    }

    @Test
    fun `the finish projection uses the calibration source`() = runTest {
        val baseline = listOf(
            WorkEstimate(CanonId("cut-duct"), EstimateCategory.PHYSICAL_WORK, 2.hours),
            WorkEstimate(CanonId("fit-grille"), EstimateCategory.ASSEMBLY, 1.hours),
        )
        val order = listOf(CanonId("cut-duct"), CanonId("fit-grille"))
        val timesOneAndAHalf = object : EstimateCalibrationSource {
            override suspend fun multiplier(category: EstimateCategory) = Calibration(1.5, samples = 4)
        }

        val uncalibrated = StandupDeliberation.schedule(
            Estimator.calibrate(baseline, NoCalibration),
            order,
            emptyList(),
            now,
        )
        val calibrated = StandupDeliberation.schedule(
            Estimator.calibrate(baseline, timesOneAndAHalf),
            order,
            emptyList(),
            now,
        )

        assertEquals(now + 3.hours, uncalibrated.projectedFinish)
        assertEquals(now + 4.hours + 30.minutes, calibrated.projectedFinish)
        assertEquals(4.hours + 30.minutes, calibrated.remainingWork)
    }

    @Test
    fun `sessions are packed into availability windows in order and may span windows`() {
        val estimates = listOf(
            CalibratedEstimate(
                WorkEstimate(CanonId("cut-duct"), EstimateCategory.PHYSICAL_WORK, 3.hours),
                Calibration.NONE,
            ),
            CalibratedEstimate(
                WorkEstimate(CanonId("fit-grille"), EstimateCategory.ASSEMBLY, 1.hours),
                Calibration.NONE,
            ),
        )
        val saturday = AvailabilityWindow(now + 1.hours, now + 3.hours)
        val sunday = AvailabilityWindow(now + 24.hours, now + 28.hours)

        val schedule = StandupDeliberation.schedule(
            estimates,
            listOf(CanonId("cut-duct"), CanonId("fit-grille")),
            listOf(sunday, saturday),
            now,
        )

        assertEquals(3, schedule.sessions.size)
        assertEquals(ProposedSession(CanonId("cut-duct"), saturday.start, saturday.end), schedule.sessions[0])
        assertEquals(ProposedSession(CanonId("cut-duct"), sunday.start, sunday.start + 1.hours), schedule.sessions[1])
        assertEquals(
            ProposedSession(CanonId("fit-grille"), sunday.start + 1.hours, sunday.start + 2.hours),
            schedule.sessions[2],
        )
        assertEquals(sunday.start + 2.hours, schedule.projectedFinish)
        assertTrue(schedule.unscheduled.isEmpty())
    }

    @Test
    fun `work that does not fit the windows is reported and the finish is null`() {
        val estimates = listOf(
            CalibratedEstimate(
                WorkEstimate(CanonId("cut-duct"), EstimateCategory.PHYSICAL_WORK, 5.hours),
                Calibration.NONE,
            ),
        )

        val schedule = StandupDeliberation.schedule(
            estimates,
            listOf(CanonId("cut-duct")),
            listOf(AvailabilityWindow(now, now + 2.hours)),
            now,
        )

        assertNull(schedule.projectedFinish)
        assertEquals(listOf(CanonId("cut-duct")), schedule.unscheduled)
        assertEquals(1, schedule.sessions.size)
    }

    @Test
    fun `an item with no estimate is skipped by the scheduler`() {
        val schedule = StandupDeliberation.schedule(emptyList(), listOf(CanonId("fit-grille")), emptyList(), now)

        assertTrue(schedule.sessions.isEmpty())
        assertNull(schedule.projectedFinish)
    }

    @Test
    fun `the templated narrative names the finish and says nothing is blocked`() {
        val proposal = RevisionProposal(
            revision = 3,
            remaining = listOf(CanonId("fit-grille")),
            estimates = emptyList(),
            sessions = emptyList(),
            remainingWork = 1.hours,
            projectedFinish = now + 1.hours,
        )
        val brief = StandupBrief(
            roomId = RoomId.forProject(project.canonId),
            projectName = "Bathroom vent",
            since = since,
            now = now,
            eventsSince = 6,
            completed = 3,
            started = 3,
            remaining = 1,
            blocked = emptyList(),
            openVerdicts = emptyList(),
            proposal = proposal,
            revisionReleased = true,
        )

        val narrative = TemplatedNarrator.render(brief)

        assertTrue(narrative.startsWith("Standup for Bathroom vent."), narrative)
        assertTrue(narrative.contains("3 task(s) finished"), narrative)
        assertTrue(narrative.contains("Projected finish ${now + 1.hours}"), narrative)
        assertTrue(narrative.contains("Revision 3 released"), narrative)
        assertTrue(narrative.endsWith("Nothing is blocked."), narrative)
    }

    @Test
    fun `the llm narrator sends the coordinator prompt and falls back to the template on failure`() = runTest {
        val proposal = RevisionProposal(1, emptyList(), emptyList(), emptyList(), 0.hours, null)
        val brief =
            StandupBrief(
                roomId = RoomId.forProject(project.canonId),
                projectName = "Bathroom vent",
                since = since,
                now = now,
                eventsSince = 0,
                completed = 0,
                started = 0,
                remaining = 0,
                blocked = emptyList(),
                openVerdicts = emptyList(),
                proposal = proposal,
                revisionReleased = true,
            )
        var prompt: String? = null

        val narrated = LlmNarrator(provider = { p -> prompt = p; "All quiet on the vent." }).narrate(brief).getOrThrow()
        val fallback = LlmNarrator(provider = { error("model down") }).narrate(brief).getOrThrow()

        assertEquals("All quiet on the vent.", narrated)
        assertTrue(prompt.orEmpty().startsWith("System: You are the Coordinator on a Blueprint."), prompt)
        assertEquals(TemplatedNarrator.render(brief), fallback)
    }
}
