package link.socket.ampere.eval.blueprint

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.relay.PlaybackRelay
import link.socket.ampere.room.Author
import link.socket.ampere.room.CoordinatorEscalation
import link.socket.ampere.room.DefaultRoomService
import link.socket.ampere.room.ReviewGate
import link.socket.ampere.room.RoomId
import link.socket.ampere.room.RoomTranscript
import link.socket.ampere.room.ThreadSubject
import link.socket.ampere.room.VerdictKind
import link.socket.ampere.room.VerdictThreadBinding
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.roster.calibration.Calibration
import link.socket.ampere.roster.calibration.EstimateCalibrationSource
import link.socket.ampere.roster.calibration.EstimateCategory
import link.socket.ampere.standup.Standup
import link.socket.ampere.standup.StandupOutcome
import link.socket.ampere.standup.TemplatedNarrator

/**
 * AMPR-379 task 5: replay the vent fixture and read the Room transcript.
 *
 * The recorded week goes through the door event by event — verdicts and lifecycle
 * events published as recorded, the roles' recorded posts re-enacted through the
 * Room so the policy counts them as passes — with the verdict binding reacting on
 * the bus exactly as it would in production. Then the standup runs over the stream
 * the door persisted. `PlaybackRelay` validates the trace and confirms it recorded
 * no model calls: a 0W standup has nothing for it to replay, and the templated
 * narrative is the replayed path.
 *
 * The criterion is the transcript: three `Violated` threads resolved and one
 * `Undetermined` escalated as a DM. [VentReplayGate] states it, and a transcript
 * that reads green fails it.
 */
class BlueprintVentReplayTest {

    private class FixedClock(var current: Instant) : Clock {
        override fun now(): Instant = current
    }

    private class Replay : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FixedClock(VentFixture.now)
        val door = InMemoryEventDoor.open(agentId = "blueprint-replay", scope = scope, clock = clock)
        val repository = MessageRepository(DEFAULT_JSON, scope, door.database)
        private val counter = AtomicInteger(0)
        val ids: () -> String = { "replay-${counter.incrementAndGet()}" }
        val room = DefaultRoomService(repository, door.api, Author.Role(BlueprintRoster.host), clock, ids)
        val binding = VerdictThreadBinding(
            room = room,
            roomId = VentFixture.roomId,
            roster = BlueprintRoster,
            escalation = CoordinatorEscalation(
                AgentMessageApi(
                    agentId = BlueprintRoster.host.value,
                    messageRepository = repository,
                    eventApi = door.api,
                ),
                scope,
            ),
            bus = door.bus,
            scope = scope,
            subjects = { it in VentFixture.subjectIds },
        )

        suspend fun awaitUntil(timeout: Duration = 10.seconds, condition: suspend () -> Boolean): Boolean =
            withTimeoutOrNull(timeout) {
                while (!condition()) delay(20)
                true
            } ?: false

        override fun close() {
            runBlocking { binding.stop() }
            scope.cancel()
            door.close()
        }
    }

    private data class ReplayResult(val transcript: String, val standup: StandupOutcome, val recordedCalls: Int)

    private suspend fun Replay.replay(): ReplayResult {
        val trace = VentFixture.trace()
        val relay = PlaybackRelay(trace)
        relay.validate().getOrThrow()

        room.open(VentFixture.graph).getOrThrow()
        binding.start()

        var verdicts = 0L
        trace.events.forEach { recorded ->
            val event = DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), recorded.payload)
            when {
                event is MessageEvent.MessagePosted && RoomId.of(event.channel) == VentFixture.roomId -> {
                    val author = Author.fromSender(event.message.sender)
                    // Thread ids are `<room>/verdict:<kind>:<subject>`; the recorded post names the thread.
                    val (kind, subjectId) = event.threadId.substringAfter("/verdict:").split(':', limit = 2)
                    val subject = ThreadSubject.Verdict(subjectId, VerdictKind.valueOf(kind.uppercase()))
                    val passes = binding.resolverPasses(subject)
                    room.post(event.threadId, author, event.message.content).getOrThrow()
                    check(
                        awaitUntil {
                            binding.resolverPasses(subject) == passes + 1
                        },
                    ) { "pass by ${author.label} on ${subject.key} was not counted" }
                }

                else -> {
                    door.api.publish(event).getOrThrow()
                    if (event is ProbeEvent.VerdictReached) {
                        verdicts += 1
                        binding.awaitVerdictsSeen(verdicts)
                        binding.awaitIdle()
                    }
                }
            }
        }

        check(
            awaitUntil { door.api.getEventHistory(eventType = MessageEvent.EscalationRequested.EVENT_TYPE).size == 1 },
        ) {
            "the grille escalation never reached the store"
        }
        check(
            awaitUntil {
                door.api.getEventHistory(eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE).size == 1
            },
        ) {
            "the Decision emission was never produced"
        }

        val standup = Standup(
            room = room,
            reviewGate = ReviewGate(room, BlueprintRoster, door.api, clock, ids),
            eventApi = door.api,
            narrator = TemplatedNarrator,
            clock = clock,
            idGenerator = ids,
        ).run(
            graph = VentFixture.graph,
            since = VentFixture.since,
            calibration = object : EstimateCalibrationSource {
                override suspend fun multiplier(category: EstimateCategory) =
                    if (category == EstimateCategory.PHYSICAL_WORK) Calibration(1.5, samples = 7) else Calibration.NONE
            },
            baseline = VentFixture.baseline,
            availability = VentFixture.availability,
        ).getOrThrow()

        val transcript = RoomTranscript.render(
            room.threads(VentFixture.roomId).getOrThrow(),
            room.history(VentFixture.roomId).getOrThrow(),
        )
        return ReplayResult(transcript, standup, relay.recordedCallCount)
    }

    @Test
    fun `the vent replay yields three resolved violations and one escalated undetermined`() = runBlocking<Unit> {
        Replay().use { replay ->
            val result = replay.replay()
            val transcript = result.transcript

            Files.createDirectories(Paths.get("build", "reports", "blueprint"))
            Files.writeString(Paths.get("build", "reports", "blueprint", "vent-transcript.md"), transcript)
            println(transcript)

            VentReplayGate.check(transcript).getOrThrow()
            assertEquals(0, result.recordedCalls, "a 0W standup records no model calls for the relay to replay")

            // The standup read the persisted stream: three completions, the grille still open, nothing blocked.
            val standup = result.standup
            assertEquals(1, standup.escalations.size)
            assertEquals("grille", standup.escalations.single().subjectId)
            assertEquals(
                listOf("pick-grille", "cut-duct", "mount-fan", "fit-grille", "verify-airflow"),
                standup.revisionProposal.remaining.map { it.value },
            )
            assertTrue(standup.revisionReleased)
            assertTrue(standup.narrative.contains("3 task(s) finished"), standup.narrative)
            assertTrue(standup.narrative.contains("Open verdicts: grille: undetermined"), standup.narrative)

            val requested = replay.door.api.getEventHistory(eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE)
                .filterIsInstance<HumanInteractionEvent.InputRequested>().single()
            assertEquals(EmissionKind.Decision, requested.emission.kind)
            assertEquals("coordinator", requested.agentId)
        }
    }

    @Test
    fun `a green transcript is a failing replay`() {
        val green = """
            # Room room:vent-42

            ## [verdict] duct (violated) — resolved · assigned to scout
            ## [verdict] fan (violated) — resolved · assigned to scout
            ## [verdict] vent-42 (violated) — resolved · assigned to planner
            ## [verdict] grille (undetermined) — resolved · assigned to scout
        """.trimIndent()

        val verdict = VentReplayGate.check(green)

        assertTrue(verdict.isFailure, "a transcript with no escalation must not pass")
        assertTrue(verdict.exceptionOrNull()?.message.orEmpty().contains("waiting for human"))
    }

    @Test
    fun `every fixture event survives the trace round-trip`() {
        val trace = VentFixture.trace()

        val decoded = trace.events.map { DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), it.payload) }

        assertEquals(VentFixture.events(), decoded)
        assertEquals(16, trace.size)
        assertEquals(6, decoded.count { it is link.socket.ampere.agents.domain.event.TaskEvent })
    }
}

/**
 * The socket#1406 Phase 3 criterion, as a check over the transcript: three
 * `Violated` threads resolved, one `Undetermined` waiting for the human after the
 * Coordinator asked, and nothing undetermined reading as resolved.
 */
object VentReplayGate {

    fun check(transcript: String): Result<Unit> {
        val resolvedViolations = Regex("""## \[verdict] \S+ \(violated\) — resolved""").findAll(transcript).count()
        val waitingUndetermined = Regex("""## \[verdict] \S+ \(undetermined\) — waiting for human""").findAll(
            transcript,
        ).count()
        val resolvedUndetermined = Regex("""## \[verdict] \S+ \(undetermined\) — resolved""")
            .findAll(transcript)
            .count()
        val asked = transcript.lineSequence().count { it.trim().startsWith("coordinator: Asking the human:") }

        val problems = buildList {
            if (resolvedViolations != 3) add("expected 3 resolved violated threads, found $resolvedViolations")
            if (waitingUndetermined != 1) {
                add(
                    "expected 1 undetermined thread waiting for human, found $waitingUndetermined",
                )
            }
            if (resolvedUndetermined != 0) add("an undetermined verdict reads as resolved: convict-but-not-acquit")
            if (asked != 1) add("expected the coordinator to ask the human once, found $asked")
        }
        return if (problems.isEmpty()) {
            Result.success(Unit)
        } else Result.failure(
            IllegalStateException(problems.joinToString("; ")),
        )
    }
}
