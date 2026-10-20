package link.socket.ampere.goal

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.GoalEvent
import link.socket.ampere.agents.domain.event.SubjectEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.canon.CanonEmailMessage
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonMilestone
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.domain.arc.GoalNode
import link.socket.ampere.domain.arc.GoalTree
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Ref
import link.socket.ampere.field.RelationshipKind
import link.socket.ampere.field.Rule
import link.socket.ampere.field.Subject
import link.socket.ampere.field.observe.environmentRef
import link.socket.ampere.link.LinkId
import link.socket.ampere.subject.SqlDelightSubjectStore

/**
 * [GoalBranch] (AMPR-419, F8): goals go onto the branch through the one door and point back at
 * the event that formed them; a run's tree round-trips; the tracker's view links as a `Mirrors`
 * edge and its `dependsOn` becomes a `Rule.Dependency` over goals.
 */
class GoalBranchTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle
    private lateinit var branch: GoalBranch

    private val runId = "run-ampr419"

    /** "Ship login and add tests" as `GoalTreeBuilder` decomposes it. */
    private val tree = GoalTree(
        root = GoalNode(
            id = "goal-0",
            description = "Ship login and add tests",
            children = listOf(
                GoalNode(id = "goal-0-0", description = "Ship login"),
                GoalNode(id = "goal-0-1", description = "add tests"),
            ),
        ),
    )

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = "ampere.arc-runtime", scope = scope)
        branch = GoalBranch.over(handle.database, handle.api)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    /** Every stored event, in fold order. */
    private suspend fun stored(): List<StoredEvent> = handle.repository.getEventsSinceSequence(0).getOrThrow()

    private suspend fun goalEvents(): List<StoredEvent> = stored().filter { it.event is GoalEvent }

    // ==================== Forming a tree ====================

    @Test
    fun `forming a tree publishes one GoalFormed per node under the run before writing the row`() =
        runBlocking<Unit> {
            branch.form(tree, runId).getOrThrow()

            val formed = goalEvents()
            assertEquals(
                listOf("$runId/goal-0", "$runId/goal-0-0", "$runId/goal-0-1"),
                formed.map { (it.event as GoalEvent.GoalFormed).goalId },
                "parents are formed before their children",
            )
            assertTrue(formed.all { it.runId == runId }, "every GoalFormed rides under the run")
            assertEquals(
                listOf(null, "$runId/goal-0", "$runId/goal-0"),
                formed.map { (it.event as GoalEvent.GoalFormed).parentId },
            )

            // Each row points back at the event that formed it, as of the envelope's recorded_at.
            val records = branch.records(runId).getOrThrow().associateBy { it.id.value }
            formed.forEach { event ->
                val record = records.getValue((event.event as GoalEvent.GoalFormed).goalId)
                assertEquals(event.event.eventId, record.goal.provenance.createdBy)
                assertEquals(event.recordedAt, record.goal.provenance.createdAt)
                assertEquals(GoalStatus.OPEN, record.status)
                assertEquals(Ref<Subject>("ampere.arc-runtime"), record.subject)
            }
        }

    @Test
    fun `a run's goal tree round-trips through the store`() = runBlocking<Unit> {
        val returned = branch.form(tree, runId).getOrThrow()

        assertEquals(tree, returned, "form returns the tree read back from the store")
        assertEquals(tree, branch.tree(runId).getOrThrow())
        assertNull(branch.tree("a-run-that-formed-nothing").getOrThrow())
    }

    @Test
    fun `forming the same run twice writes nothing new`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()
        val before = stored().size

        assertEquals(tree, branch.form(tree, runId).getOrThrow())
        assertEquals(before, stored().size, "no event and no row for goals already on the branch")
        assertEquals(3, branch.records(runId).getOrThrow().size)
    }

    @Test
    fun `the holder is registered as a Subject before its first goal`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()

        val registered = stored().map { it.event }.filterIsInstance<SubjectEvent.SubjectRegistered>()
        assertEquals(listOf("ampere.arc-runtime"), registered.map { it.subjectId })
        val holder = SqlDelightSubjectStore(handle.database).get(branch.holder).getOrThrow()
        assertIs<Subject.Agent>(holder)
    }

    // ==================== Moving a goal ====================

    @Test
    fun `completing a goal publishes GoalUpdated under the run and moves the row`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()

        val completed = branch.complete(runId, "goal-0-0").getOrThrow()

        val updated = goalEvents().single { it.event is GoalEvent.GoalUpdated }
        val event = updated.event as GoalEvent.GoalUpdated
        assertEquals("$runId/goal-0-0", event.goalId)
        assertEquals(GoalStatus.COMPLETED.name, event.status)
        assertEquals(runId, updated.runId)
        assertEquals(GoalStatus.COMPLETED, completed.status)
        assertEquals(event.eventId, completed.lastUpdate.createdBy)
        assertEquals(
            listOf("$runId/goal-0", "$runId/goal-0-1"),
            branch.open().getOrThrow().map { it.id.value },
            "the completed goal leaves the holder's open goals",
        )
    }

    @Test
    fun `abandoning a goal publishes GoalUpdated under the run and moves the row`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()

        val abandoned = branch.abandon(runId, "goal-0").getOrThrow()

        val updated = goalEvents().single { it.event is GoalEvent.GoalUpdated }
        val event = updated.event as GoalEvent.GoalUpdated
        assertEquals("$runId/goal-0", event.goalId)
        assertEquals(GoalStatus.ABANDONED.name, event.status)
        assertEquals(runId, updated.runId)
        assertEquals(GoalStatus.ABANDONED, abandoned.status)
        assertEquals(event.eventId, abandoned.lastUpdate.createdBy)
        assertEquals(GoalStatus.ABANDONED, branch.records(runId).getOrThrow().single { it.id == abandoned.id }.status)
        assertEquals(
            listOf("$runId/goal-0-0", "$runId/goal-0-1"),
            branch.open().getOrThrow().map { it.id.value },
            "the abandoned goal leaves the holder's open goals",
        )
    }

    @Test
    fun `an agent registered through the branch is a Subject beside the holder`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()

        branch.registerAgent("seat.scout").getOrThrow()
        branch.registerAgent("seat.scout").getOrThrow()

        val registered = stored().map { it.event }.filterIsInstance<SubjectEvent.SubjectRegistered>()
        assertEquals(listOf("ampere.arc-runtime", "seat.scout"), registered.map { it.subjectId })
        assertIs<Subject.Agent>(SqlDelightSubjectStore(handle.database).get(Ref("seat.scout")).getOrThrow())
    }

    @Test
    fun `completing a goal twice announces it once`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()

        branch.complete(runId, "goal-0").getOrThrow()
        branch.complete(runId, "goal-0").getOrThrow()

        assertEquals(1, goalEvents().count { it.event is GoalEvent.GoalUpdated })
    }

    @Test
    fun `moving a goal nothing formed is refused`() = runBlocking<Unit> {
        val failure = branch.complete(runId, "goal-9").exceptionOrNull()

        assertIs<UnknownGoalException>(failure)
        assertTrue(goalEvents().isEmpty(), "a refused move announces nothing")
    }

    // ==================== Mirrors ====================

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(linkId = LinkId("linear-1"), sourceSystem = "mcp:linear", nativeId = "AMPR-419"),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    private fun workItem(id: String, dependsOn: List<String> = emptyList()) = CanonWorkItem(
        canonId = CanonId(id),
        provenance = provenance,
        title = "Work item $id",
        status = CanonWorkStatus.IN_PROGRESS,
        dependsOn = dependsOn.map(::CanonId),
    )

    @Test
    fun `a perceived work item mirrors a goal through one Mirrors edge`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()
        val goal = GoalBranch.goalRef(runId, "goal-0-0")

        val login = workItem("wi-login")
        val edge = branch.mirror(login, goal).getOrThrow()

        assertEquals("canon/linear-1/wi-login", edge.from.value, "the item's Link-qualified Environment ref")
        assertEquals(login.environmentRef, edge.from)
        assertEquals(goal.value, edge.to.value)
        assertEquals(RelationshipKind.Mirrors, edge.kind)
        val recorded = stored().map { it.event }.filterIsInstance<SubjectEvent.RelationshipRecorded>().single()
        assertEquals(recorded.eventId, edge.provenance.createdBy, "the edge points back at its event")
        assertEquals("Mirrors", recorded.kind)

        assertEquals(listOf(login.environmentRef), branch.mirrorsOf(goal).getOrThrow())
        assertEquals(listOf(goal), branch.goalsMirroredBy(login.environmentRef).getOrThrow())

        // The tracker's view mirrors the goal; it does not replace it.
        val held = branch.records(runId).getOrThrow().single { it.id == goal }
        assertEquals("Ship login", held.goal.description)
        assertEquals(GoalStatus.OPEN, held.status)
    }

    @Test
    fun `a project and a milestone mirror goals too`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()
        val root = GoalBranch.goalRef(runId, "goal-0")
        val project = CanonProject(
            canonId = CanonId("proj-auth"),
            provenance = provenance,
            name = "Auth",
            status = CanonWorkStatus.IN_PROGRESS,
        )
        val milestone = CanonMilestone(canonId = CanonId("ms-beta"), provenance = provenance, name = "Beta")

        branch.mirror(project, root).getOrThrow()
        branch.mirror(milestone, root).getOrThrow()

        // A set: two edges recorded in the same millisecond tie on created_at.
        assertEquals(
            setOf(project.environmentRef, milestone.environmentRef),
            branch.mirrorsOf(root).getOrThrow().toSet(),
        )
    }

    @Test
    fun `only tracker work can mirror a goal`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()
        val email = CanonEmailMessage(
            canonId = CanonId("mail-1"),
            provenance = provenance,
            subject = "Login",
            from = null,
        )

        val failure = branch.mirror(email, GoalBranch.goalRef(runId, "goal-0")).exceptionOrNull()

        assertIs<NotTrackerWorkException>(failure)
        assertTrue(stored().none { it.event is SubjectEvent.RelationshipRecorded })
    }

    @Test
    fun `a work item cannot mirror a goal nothing formed`() = runBlocking<Unit> {
        val failure = branch.mirror(workItem("wi-1"), Ref<Goal>("$runId/goal-0")).exceptionOrNull()

        assertIs<UnknownGoalException>(failure)
        assertTrue(stored().none { it.event is SubjectEvent.RelationshipRecorded }, "no dangling edge")
    }

    @Test
    fun `dependsOn becomes a Dependency rule over the goals its dependencies mirror`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()
        val login = GoalBranch.goalRef(runId, "goal-0-0")
        branch.mirror(workItem("wi-login"), login).getOrThrow()

        val tests = workItem("wi-tests", dependsOn = listOf("wi-login", "wi-unseen"))
        val mapping = branch.dependencies(tests).getOrThrow()

        assertEquals(Rule.Dependency(dependsOn = setOf(login)), mapping.rule)
        assertEquals(listOf(CanonId("wi-unseen")), mapping.unresolved, "a dependency mirroring no goal is kept apart")
    }

    @Test
    fun `a work item that depends on nothing produces no rule`() = runBlocking<Unit> {
        val mapping = branch.dependencies(workItem("wi-alone")).getOrThrow()

        assertNull(mapping.rule)
        assertTrue(mapping.unresolved.isEmpty())
    }
}
