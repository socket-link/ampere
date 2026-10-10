package link.socket.ampere.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.Verdict
import link.socket.ampere.probe.safety.HazardCategory
import link.socket.ampere.probe.safety.KeywordHazardClassifier
import link.socket.ampere.probe.safety.MitigationHint
import link.socket.ampere.probe.safety.MitigationPlan
import link.socket.ampere.probe.safety.PlanLine
import link.socket.ampere.probe.safety.SafetyProbe
import link.socket.ampere.probe.safety.WorkPlan
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig

/**
 * AMPR-380 task 2: the Inspector's hazard rule over a real Room — a thread per
 * finding, a mitigation Task before each hazardous Task, and nothing new on a
 * second look.
 */
class SafetyReviewTest {

    private val probe = SafetyProbe(KeywordHazardClassifier)

    private val items = listOf(
        VentGraph.item("order-parts"),
        VentGraph.item("cut-duct", CanonWorkStatus.TODO, "order-parts")
            .copy(title = "Cut the duct to length"),
        VentGraph.item("mount-fan", CanonWorkStatus.TODO, "cut-duct")
            .copy(title = "Mount the fan and wire it to mains"),
    )

    private val graph: CanonWorkGraph = VentGraph.graph(items)

    private val lines = listOf(
        PlanLine("line-fan", "MAINS_VOLTAGE", "Inline exhaust fan", setOf(CanonId("mount-fan"))),
        PlanLine("line-sealant", "RESIN", "Two-part duct sealant", setOf(CanonId("mount-fan"))),
    )

    private val plan = WorkPlan(graph, lines)

    private fun <T> withReview(
        roster: Roster = BlueprintRoster,
        block: suspend (RoomTestRig, SafetyReview) -> T,
    ): T =
        RoomTestRig("safety-review").use { rig ->
            runBlocking {
                val roomId = rig.room.open(graph).getOrThrow()
                block(rig, SafetyReview(rig.room, roomId, roster, probe, rig.clock))
            }
        }

    @Test
    fun `one thread per finding assigned to the planner and posted by the inspector`() = withReview { rig, review ->
        val outcome = review.review(plan).getOrThrow()

        assertIs<Verdict.Warn>(outcome.verdict)
        assertEquals(
            listOf(
                "room:vent-42/hazard:cutting_or_power_tools:cut-duct",
                "room:vent-42/hazard:electrical:mount-fan",
                "room:vent-42/hazard:fumes_or_chemicals:mount-fan",
            ),
            outcome.threads,
        )

        val threads = rig.room.threads(RoomId.forProject(graph.project.canonId)).getOrThrow()
        val hazards = threads.filter { it.subject is ThreadSubject.Hazard }
        assertEquals(3, hazards.size)
        assertTrue(hazards.all { it.assignedTo == BlueprintRoster.planner.id })

        val history = rig.room.history(RoomId.forProject(graph.project.canonId)).getOrThrow()
        val cards = history.mapNotNull { it.card as? RoomCard.Hazard }
        assertEquals(3, cards.size)
        assertEquals(
            listOf(Author.Role(BlueprintRoster.inspector.id)),
            history.filter { it.card is RoomCard.Hazard }.map { it.author }.distinct(),
        )
        assertEquals(
            RoomCard.Hazard(
                category = HazardCategory.FUMES_OR_CHEMICALS,
                subjectId = "mount-fan",
                mitigationHint = MitigationHint.CONFIRM_VENTILATION,
                evidence = "manifest line line-sealant has kind RESIN",
                mitigationTaskId = CanonId("mount-fan/mitigation:confirm_ventilation"),
            ),
            cards.last(),
        )
    }

    @Test
    fun `a mitigation task is inserted before each hazardous task with no cycles`() = withReview { _, review ->
        val outcome = review.review(plan).getOrThrow()

        assertEquals(
            listOf(
                "order-parts",
                "cut-duct/mitigation:use_ppe",
                "cut-duct",
                "mount-fan/mitigation:check_local_code",
                "mount-fan/mitigation:confirm_ventilation",
                "mount-fan",
            ),
            outcome.graph.items.map { it.canonId.value },
        )
        assertEquals(
            listOf("cut-duct", "mount-fan/mitigation:check_local_code", "mount-fan/mitigation:confirm_ventilation"),
            outcome.graph.items.single { it.canonId.value == "mount-fan" }.dependsOn.map { it.value },
        )
        assertEquals(Verdict.Holds(), SequenceProbe().evaluate(outcome.graph))
        assertTrue(outcome.inserted.all { MitigationPlan.isMitigation(it.task) })
    }

    @Test
    fun `reviewing the same plan twice opens no new thread and posts no new card`() = withReview { rig, review ->
        val roomId = RoomId.forProject(graph.project.canonId)
        val first = review.review(plan).getOrThrow()
        val postsAfterFirst = rig.room.history(roomId).getOrThrow().size

        val second = review.review(WorkPlan(first.graph, lines)).getOrThrow()

        assertEquals(3, first.openedThreads.size)
        assertEquals(emptyList(), second.openedThreads)
        assertEquals(first.threads, second.threads)
        assertEquals(emptyList(), second.inserted)
        assertEquals(postsAfterFirst, rig.room.history(roomId).getOrThrow().size)
    }

    @Test
    fun `a plan with no hazard opens nothing`() = withReview { rig, review ->
        val safe = VentGraph.graph(listOf(VentGraph.item("order-parts"), VentGraph.item("wait-for-delivery")))

        val outcome = review.review(WorkPlan(safe)).getOrThrow()

        assertEquals(Verdict.Holds(reason = "no hazard category in 2 task(s)"), outcome.verdict)
        assertEquals(emptyList(), outcome.threads)
        assertTrue(
            rig.room.threads(RoomId.forProject(graph.project.canonId)).getOrThrow()
                .none { it.subject is ThreadSubject.Hazard },
        )
    }

    @Test
    fun `a hazard on a line no task uses opens a thread with no mitigation task`() = withReview { rig, review ->
        val orphan = WorkPlan(
            graph = VentGraph.graph(listOf(VentGraph.item("order-parts"))),
            lines = listOf(PlanLine("line-solvent", "SOLVENT", "Acetone")),
        )

        val outcome = review.review(orphan).getOrThrow()

        assertEquals(listOf("room:vent-42/hazard:fumes_or_chemicals:line-solvent"), outcome.threads)
        assertEquals(emptyList(), outcome.inserted)
        val card = rig.room.history(RoomId.forProject(graph.project.canonId)).getOrThrow()
            .mapNotNull { it.card as? RoomCard.Hazard }
            .single()
        assertNull(card.mitigationTaskId)
        val body = rig.room.history(RoomId.forProject(graph.project.canonId)).getOrThrow()
            .single { it.card is RoomCard.Hazard }
            .body
        assertTrue(body.contains("No task in the plan uses this line"), body)
    }

    @Test
    fun `the transcript shows every hazard thread as open`() = withReview { rig, review ->
        review.review(plan).getOrThrow()
        val roomId = RoomId.forProject(graph.project.canonId)

        val transcript = RoomTranscript.render(
            rig.room.threads(roomId).getOrThrow(),
            rig.room.history(roomId).getOrThrow(),
        )

        assertEquals(
            3,
            Regex("""## \[hazard] \S+ \(\w+\) — open · assigned to planner""").findAll(transcript).count(),
            transcript,
        )
        assertTrue(transcript.contains("hazard ELECTRICAL on mount-fan: CHECK_LOCAL_CODE"), transcript)
    }

    @Test
    fun `the room publishes a thread opened event per hazard`() = withReview { rig, review ->
        review.review(plan).getOrThrow()

        val opened = rig.events(RoomEvent.ThreadOpened.EVENT_TYPE)
            .filterIsInstance<RoomEvent.ThreadOpened>()
            .mapNotNull { it.subject as? ThreadSubject.Hazard }

        // The store returns newest first; this is about which events exist.
        assertEquals(
            setOf(
                HazardCategory.CUTTING_OR_POWER_TOOLS,
                HazardCategory.ELECTRICAL,
                HazardCategory.FUMES_OR_CHEMICALS,
            ),
            opened.map { it.category }.toSet(),
        )
        assertEquals(3, opened.size)
    }

    /**
     * AMPR-409: the mitigation Tasks are graph work and go in regardless; what a
     * roster with no reviewing seat lacks is somebody to hold the conversation.
     */
    @Test
    fun `a roster with no verifier mitigates the plan and opens no hazard thread`() {
        val solo = RoleConfig(RoleId("solo"), "Solo", PromptRef("consumer.solo", 1))
        withReview(RosterConfig(host = solo.id, roles = listOf(solo))) { rig, review ->
            val outcome = review.review(plan).getOrThrow()

            assertIs<Verdict.Warn>(outcome.verdict)
            assertEquals(3, outcome.inserted.size)
            assertEquals(
                listOf(
                    "order-parts",
                    "cut-duct/mitigation:use_ppe",
                    "cut-duct",
                    "mount-fan/mitigation:check_local_code",
                    "mount-fan/mitigation:confirm_ventilation",
                    "mount-fan",
                ),
                outcome.graph.items.map { it.canonId.value },
            )
            assertEquals(emptyList(), outcome.threads)
            assertEquals(emptyList(), outcome.openedThreads)

            val roomId = RoomId.forProject(graph.project.canonId)
            assertTrue(rig.room.threads(roomId).getOrThrow().none { it.subject is ThreadSubject.Hazard })
            assertTrue(rig.room.history(roomId).getOrThrow().none { it.card is RoomCard.Hazard })
        }
    }
}
