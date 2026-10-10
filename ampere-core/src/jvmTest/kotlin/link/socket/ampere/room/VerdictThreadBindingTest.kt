package link.socket.ampere.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig

/**
 * AMPR-379 tasks 2 and 3 validation, the bus half: the fixture's three `Violated`
 * and one `Undetermined` verdicts yield four correctly assigned threads; the
 * `Undetermined` escalates as a Decision-kind CHI at once; the `Violated` ones do
 * not DM the human until unresolved after one Scout pass; and a `Holds` closes.
 */
class VerdictThreadBindingTest {

    private val partFit = ProbeId("blueprint.part-fit")
    private val sequence = ProbeId(SequenceProbe.ID)

    private class Bound(val rig: RoomTestRig, val roomId: RoomId, val binding: VerdictThreadBinding) : AutoCloseable {
        var published = 0L

        suspend fun verdict(subjectId: String, verdict: ProbeVerdict, probeId: ProbeId): ProbeEvent.VerdictReached {
            val event = ProbeEvent.VerdictReached(
                eventId = rig.ids(),
                eventSource = EventSource.Agent("inspector"),
                timestamp = rig.clock.now(),
                probeId = probeId,
                subjectId = subjectId,
                verdict = verdict,
            )
            rig.door.api.publish(event).getOrThrow()
            published += 1
            binding.awaitVerdictsSeen(published)
            binding.awaitIdle()
            return event
        }

        suspend fun escalations(): List<MessageEvent.EscalationRequested> =
            rig.events(MessageEvent.EscalationRequested.EVENT_TYPE).filterIsInstance<MessageEvent.EscalationRequested>()

        suspend fun thread(subject: ThreadSubject.Verdict): RoomThread =
            rig.room.threads(roomId).getOrThrow().single { it.subject == subject }

        override fun close() {
            runBlocking { binding.stop() }
            rig.close()
        }
    }

    private suspend fun bind(roster: Roster = BlueprintRoster): Bound {
        val rig = RoomTestRig()
        val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
        val coordinatorApi = AgentMessageApi(
            agentId = roster.host.value,
            messageRepository = rig.repository,
            eventApi = rig.door.api,
        )
        val binding = VerdictThreadBinding(
            room = rig.room,
            roomId = roomId,
            roster = roster,
            escalation = CoordinatorEscalation(coordinatorApi, rig.scope),
            bus = rig.door.bus,
            scope = rig.scope,
            subjects = { it in VentGraph.subjectIds },
        )
        binding.start()
        return Bound(rig, roomId, binding)
    }

    @Test
    fun `three violated and one undetermined verdicts yield four correctly assigned threads`() = runBlocking<Unit> {
        bind().use { bound ->
            bound.verdict("duct", ProbeVerdict.Violated("duct undersized: 4in run for 226 CFM"), partFit)
            bound.verdict("fan", ProbeVerdict.Violated("fan lead time 3 weeks exceeds the Source milestone"), partFit)
            bound.verdict("vent-42", ProbeVerdict.Violated("dangling dependsOn: fit-grille -> mount-grille"), sequence)
            bound.verdict(
                "grille",
                ProbeVerdict.Undetermined("grille publishes no CFM", UndeterminedCause.EVIDENCE_ABSENT),
                partFit,
            )

            val threads = bound.rig.room.threads(bound.roomId).getOrThrow()
                .filter { it.subject is ThreadSubject.Verdict }
            assertEquals(4, threads.size)
            assertEquals(
                BlueprintRoster.scout.id,
                bound.thread(ThreadSubject.Verdict("duct", VerdictKind.VIOLATED)).assignedTo,
            )
            assertEquals(
                BlueprintRoster.scout.id,
                bound.thread(ThreadSubject.Verdict("fan", VerdictKind.VIOLATED)).assignedTo,
            )
            assertEquals(
                BlueprintRoster.planner.id,
                bound.thread(ThreadSubject.Verdict("vent-42", VerdictKind.VIOLATED)).assignedTo,
            )
            assertEquals(
                BlueprintRoster.scout.id,
                bound.thread(ThreadSubject.Verdict("grille", VerdictKind.UNDETERMINED)).assignedTo,
            )
            assertEquals(
                4,
                bound.rig.events(RoomEvent.Posted.EVENT_TYPE).filterIsInstance<RoomEvent.Posted>().count {
                    it.card is RoomCard.Verdict
                },
            )
            assertEquals(4, bound.binding.openThreads().size)
        }
    }

    @Test
    fun `the undetermined verdict escalates as a decision-kind CHI and the violated ones do not`() = runBlocking<Unit> {
        bind().use { bound ->
            bound.verdict("duct", ProbeVerdict.Violated("duct undersized"), partFit)
            bound.verdict("fan", ProbeVerdict.Violated("fan lead time"), partFit)
            bound.verdict("vent-42", ProbeVerdict.Violated("dangling dependsOn"), sequence)
            bound.verdict(
                "grille",
                ProbeVerdict.Undetermined("grille publishes no CFM", UndeterminedCause.EVIDENCE_ABSENT),
                partFit,
            )

            val grille = ThreadSubject.Verdict("grille", VerdictKind.UNDETERMINED)
            assertTrue(
                bound.rig.awaitUntil(10.seconds) { bound.escalations().size == 1 },
                "the grille escalation never landed",
            )
            assertTrue(
                bound.rig.awaitUntil(10.seconds) {
                    bound.rig.events(HumanInteractionEvent.InputRequested.EVENT_TYPE).isNotEmpty()
                },
                "no Decision emission was produced",
            )

            val escalation = bound.escalations().single()
            assertEquals(roomThreadId(bound.roomId, grille), escalation.threadId)
            assertEquals(EventSource.Agent("coordinator"), escalation.eventSource)
            val requested = bound.rig.events(HumanInteractionEvent.InputRequested.EVENT_TYPE)
                .filterIsInstance<HumanInteractionEvent.InputRequested>().single()
            assertEquals(EmissionKind.Decision, requested.emission.kind)
            assertTrue(assertIs<EmissionPayload.Decision>(requested.emission.payload).prompt.contains("grille"))
            assertEquals("coordinator", requested.agentId)
            assertEquals(EventStatus.WaitingForHuman, bound.thread(grille).status)

            listOf("duct", "fan", "vent-42").forEach { subject ->
                assertEquals(
                    EventStatus.Open,
                    bound.thread(ThreadSubject.Verdict(subject, VerdictKind.VIOLATED)).status,
                    subject,
                )
            }
            val notices = bound.rig.room.history(bound.roomId).getOrThrow().filter {
                it.body.startsWith(
                    "Asking the human",
                )
            }
            assertEquals(1, notices.size)
            assertEquals(Author.Role(BlueprintRoster.coordinator.id), notices.single().author)
        }
    }

    @Test
    fun `a violation still standing after one scout pass is escalated`() = runBlocking<Unit> {
        bind().use { bound ->
            val duct = ThreadSubject.Verdict("duct", VerdictKind.VIOLATED)
            bound.verdict("duct", ProbeVerdict.Violated("duct undersized"), partFit)
            assertTrue(bound.escalations().isEmpty())

            bound.rig.room.post(
                roomThreadId(bound.roomId, duct),
                Author.Role(BlueprintRoster.scout.id),
                "Tried a 5in duct: still short",
            ).getOrThrow()
            assertTrue(
                bound.rig.awaitUntil { bound.binding.resolverPasses(duct) == 1 },
                "the scout pass was not counted",
            )
            bound.verdict("duct", ProbeVerdict.Violated("5in duct still undersized"), partFit)

            assertTrue(
                bound.rig.awaitUntil(10.seconds) { bound.escalations().size == 1 },
                "no escalation after the scout pass",
            )
            assertEquals(roomThreadId(bound.roomId, duct), bound.escalations().single().threadId)
            assertTrue(bound.escalations().single().reason.contains("5in duct still undersized"))
        }
    }

    @Test
    fun `a holding verdict closes the thread it convicted and ignores others`() = runBlocking<Unit> {
        bind().use { bound ->
            val fan = ThreadSubject.Verdict("fan", VerdictKind.VIOLATED)
            bound.verdict("fan", ProbeVerdict.Violated("fan lead time"), partFit)
            bound.verdict("fan", ProbeVerdict.Holds("4-day lead time"), sequence)
            assertEquals(EventStatus.Open, bound.thread(fan).status, "a different probe's holds leaves the thread open")

            bound.verdict("fan", ProbeVerdict.Holds("4-day lead time"), partFit)

            assertEquals(EventStatus.Resolved, bound.thread(fan).status)
            assertTrue(bound.binding.openThreads().isEmpty())
            val resolved = bound.rig.events(RoomEvent.ThreadResolved.EVENT_TYPE)
                .filterIsInstance<RoomEvent.ThreadResolved>()
                .single()
            assertEquals("4-day lead time", resolved.reason)
            assertEquals(Author.Role(BlueprintRoster.inspector.id), resolved.resolvedBy)
            assertTrue(bound.escalations().isEmpty())
        }
    }

    @Test
    fun `a verdict on a subject outside the room opens nothing`() = runBlocking<Unit> {
        bind().use { bound ->
            bound.verdict("someone-elses-plan", ProbeVerdict.Violated("cycle"), sequence)

            assertEquals(5, bound.rig.room.threads(bound.roomId).getOrThrow().size)
            assertTrue(bound.binding.openThreads().isEmpty())
        }
    }

    /** AMPR-409: the binding over a roster with no reviewing seat observes and writes nothing. */
    @Test
    fun `a roster with no verifier opens no thread and still counts what it saw`() = runBlocking<Unit> {
        val solo = RoleConfig(RoleId("solo"), "Solo", PromptRef("consumer.solo", 1))
        bind(RosterConfig(host = solo.id, roles = listOf(solo))).use { bound ->
            bound.verdict("duct", ProbeVerdict.Violated("duct undersized"), partFit)
            bound.verdict(
                "grille",
                ProbeVerdict.Undetermined("grille publishes no CFM", UndeterminedCause.EVIDENCE_ABSENT),
                partFit,
            )

            assertEquals(2L, bound.binding.verdictsSeen.value)
            assertTrue(bound.binding.openThreads().isEmpty())
            assertTrue(bound.escalations().isEmpty())
            assertTrue(
                bound.rig.room.threads(bound.roomId).getOrThrow().none { it.subject is ThreadSubject.Verdict },
            )
            assertTrue(bound.rig.events(RoomEvent.Posted.EVENT_TYPE).isEmpty())
        }
    }
}
