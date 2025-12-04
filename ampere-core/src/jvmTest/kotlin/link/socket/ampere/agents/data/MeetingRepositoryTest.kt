package link.socket.ampere.agents.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.concept.status.MeetingStatus
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.MeetingTask.AgendaItem
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.meetings.Meeting
import link.socket.ampere.agents.events.meetings.MeetingInvitation
import link.socket.ampere.agents.events.meetings.MeetingMessagingDetails
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.meetings.MeetingType
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class MeetingRepositoryTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val stubJson = DEFAULT_JSON

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: MeetingRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)
        repo = MeetingRepository(stubJson, testScope, database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Helper Methods ====================

    private fun createScheduledMeeting(
        id: String = "meeting-1",
        title: String = "Daily Standup",
        scheduledFor: Instant = Clock.System.now() + 1.hours,
        requiredParticipants: List<AssignedTo> = listOf(
            AssignedTo.Agent("agent-alpha"),
            AssignedTo.Agent("agent-beta"),
        ),
        optionalParticipants: List<AssignedTo>? = null,
        agenda: List<AgendaItem> = listOf(
            AgendaItem(
                id = "ai-1",
                title = "Yesterday updates",
                assignedTo = AssignedTo.Agent("agent-alpha"),
            ),
            AgendaItem(
                id = "ai-2",
                title = "Today plans",
                assignedTo = AssignedTo.Agent("agent-beta"),
            ),
        ),
    ) = Meeting(
        id = id,
        type = MeetingType.Standup(
            teamId = "team-1",
            sprintId = "sprint-1",
        ),
        status = MeetingStatus.Scheduled(
            scheduledFor = scheduledFor,
        ),
        invitation = MeetingInvitation(
            title = title,
            agenda = agenda,
            requiredParticipants = requiredParticipants,
            optionalParticipants = optionalParticipants,
        ),
    )

    private fun createCompletedMeeting(
        id: String = "meeting-completed",
        title: String = "Sprint Planning",
        completedAt: Instant = Clock.System.now(),
        attendees: List<EventSource> = listOf(
            EventSource.Agent("agent-alpha"),
            EventSource.Agent("agent-beta"),
        ),
        outcomes: List<MeetingOutcome> = listOf(
            MeetingOutcome.DecisionMade(
                id = "outcome-1",
                description = "Proceed with refactor",
                decidedBy = EventSource.Agent("agent-alpha"),
            ),
        ),
    ) = Meeting(
        id = id,
        type = MeetingType.SprintPlanning(
            teamId = "team-1",
            sprintId = "sprint-2",
        ),
        status = MeetingStatus.Completed(
            completedAt = completedAt,
            attendedBy = attendees,
            messagingDetails = MeetingMessagingDetails(
                messageChannelId = "channel-1",
                messageThreadId = "thread-1",
            ),
            outcomes = outcomes,
        ),
        invitation = MeetingInvitation(
            title = title,
            agenda = listOf(
                AgendaItem(
                    id = "ai-plan-1",
                    title = "Review sprint goals",
                    status = TaskStatus.Completed(
                        completedAt = completedAt,
                        completedBy = EventSource.Agent("agent-alpha"),
                    ),
                ),
            ),
            requiredParticipants = listOf(AssignedTo.Agent("agent-alpha")),
        ),
    )

    // ==================== Tests ====================

    @Test
    fun `create and retrieve meeting by id`() { runBlocking {
        val meeting = createScheduledMeeting()

        val saveResult = repo.saveMeeting(meeting)
        assertTrue(saveResult.isSuccess, "Failed to save meeting: ${saveResult.exceptionOrNull()}")

        val loaded = repo.getMeeting(meeting.id).getOrNull()
        assertNotNull(loaded)
        assertEquals(meeting.id, loaded.id)
        assertEquals(meeting.invitation.title, loaded.invitation.title)
        assertIs<MeetingType.Standup>(loaded.type)
        assertIs<MeetingStatus.Scheduled>(loaded.status)
    } }

    @Test
    fun `retrieve meeting preserves all invitation fields`() { runBlocking {
        val meeting = createScheduledMeeting(
            optionalParticipants = listOf(AssignedTo.Human),
        )
        repo.saveMeeting(meeting)

        val loaded = repo.getMeeting(meeting.id).getOrNull()
        assertNotNull(loaded)

        assertEquals(2, loaded.invitation.agenda.size)
        assertEquals("Yesterday updates", loaded.invitation.agenda[0].title)
        assertEquals("Today plans", loaded.invitation.agenda[1].title)

        assertEquals(2, loaded.invitation.requiredParticipants.size)
        assertNotNull(loaded.invitation.optionalParticipants)
        assertEquals(1, loaded.invitation.optionalParticipants.size)
        assertIs<AssignedTo.Human>(loaded.invitation.optionalParticipants[0])
    } }

    @Test
    fun `retrieve meeting with agenda item assignments`() { runBlocking {
        val meeting = createScheduledMeeting()
        repo.saveMeeting(meeting)

        val loaded = repo.getMeeting(meeting.id).getOrNull()
        assertNotNull(loaded)

        val firstItem = loaded.invitation.agenda[0]
        assertNotNull(firstItem.assignedTo)
        assertEquals("agent-alpha", firstItem.assignedTo.getIdentifier())

        val secondItem = loaded.invitation.agenda[1]
        assertNotNull(secondItem.assignedTo)
        assertEquals("agent-beta", secondItem.assignedTo.getIdentifier())
    } }

    @Test
    fun `create and retrieve completed meeting with outcomes`() { runBlocking {
        val meeting = createCompletedMeeting()
        repo.saveMeeting(meeting)

        val loaded = repo.getMeeting(meeting.id).getOrNull()
        assertNotNull(loaded)

        assertIs<MeetingStatus.Completed>(loaded.status)
        val completedStatus = loaded.status

        assertEquals(2, completedStatus.attendedBy.size)
        assertNotNull(completedStatus.outcomes)
        assertEquals(1, completedStatus.outcomes!!.size)
        assertIs<MeetingOutcome.DecisionMade>(completedStatus.outcomes!![0])
    } }

    @Test
    fun `update meeting status from scheduled to in_progress`() { runBlocking {
        val meeting = createScheduledMeeting()
        repo.saveMeeting(meeting)

        val newStatus = MeetingStatus.InProgress(
            startedAt = Clock.System.now(),
            messagingDetails = MeetingMessagingDetails(
                messageChannelId = "channel-1",
                messageThreadId = "thread-1",
            ),
        )

        val updateResult = repo.updateMeetingStatus(meeting.id, newStatus)
        assertTrue(updateResult.isSuccess)

        val loaded = repo.getMeeting(meeting.id).getOrNull()
        assertNotNull(loaded)
        assertIs<MeetingStatus.InProgress>(loaded.status)

        val inProgressStatus = loaded.status
        assertEquals("channel-1", inProgressStatus.messagingDetails.messageChannelId)
    } }

    @Test
    fun `update meeting status to completed with outcomes and attendees`() { runBlocking {
        val meeting = createScheduledMeeting()
        repo.saveMeeting(meeting)

        val completedStatus = MeetingStatus.Completed(
            completedAt = Clock.System.now(),
            attendedBy = listOf(
                EventSource.Agent("agent-alpha"),
                EventSource.Agent("agent-beta"),
            ),
            messagingDetails = MeetingMessagingDetails(
                messageChannelId = "channel-1",
                messageThreadId = "thread-1",
            ),
            outcomes = listOf(
                MeetingOutcome.ActionItem(
                    id = "action-1",
                    assignedTo = AssignedTo.Agent("agent-alpha"),
                    description = "Implement feature X",
                    dueBy = Clock.System.now() + 24.hours,
                ),
            ),
        )

        repo.updateMeetingStatus(meeting.id, completedStatus)

        val loaded = repo.getMeeting(meeting.id).getOrNull()
        assertNotNull(loaded)
        assertIs<MeetingStatus.Completed>(loaded.status)

        val status = loaded.status as MeetingStatus.Completed
        assertEquals(2, status.attendedBy.size)
        assertEquals(1, status.outcomes?.size)
        assertIs<MeetingOutcome.ActionItem>(status.outcomes!![0])
    } }

    @Test
    fun `add outcome to meeting`() { runBlocking {
        val meeting = createScheduledMeeting()
        repo.saveMeeting(meeting)

        val outcome = MeetingOutcome.BlockerRaised(
            id = "blocker-1",
            description = "API not available",
            raisedBy = EventSource.Agent("agent-alpha"),
            assignedTo = AssignedTo.Agent("agent-beta"),
        )

        val result = repo.addOutcome(meeting.id, outcome)
        assertTrue(result.isSuccess)

        // Retrieve and verify (note: outcomes are stored but only returned for completed/canceled status)
        val database = Database.Companion(driver)
        val outcomeRows = database.meetingStoreQueries.getOutcomesForMeeting(meeting.id).executeAsList()
        assertEquals(1, outcomeRows.size)
        assertEquals("BLOCKER_RAISED", outcomeRows[0].outcomeType)
    } }

    @Test
    fun `get scheduled meetings before time`() { runBlocking {
        val now = Clock.System.now()

        // Meeting scheduled in 30 minutes
        val meeting1 = createScheduledMeeting(
            id = "meeting-soon",
            scheduledFor = now + 30.minutes,
        )

        // Meeting scheduled in 2 hours
        val meeting2 = createScheduledMeeting(
            id = "meeting-later",
            scheduledFor = now + 2.hours,
        )

        repo.saveMeeting(meeting1)
        repo.saveMeeting(meeting2)

        // Get meetings scheduled before 1 hour from now
        val result = repo.getScheduledMeetings(now + 1.hours).getOrNull()
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("meeting-soon", result[0].id)
    } }

    @Test
    fun `get meetings for participant`() { runBlocking {
        val meeting1 = createScheduledMeeting(
            id = "meeting-1",
            requiredParticipants = listOf(
                AssignedTo.Agent("agent-alpha"),
                AssignedTo.Agent("agent-beta"),
            ),
        )

        val meeting2 = createScheduledMeeting(
            id = "meeting-2",
            requiredParticipants = listOf(
                AssignedTo.Agent("agent-alpha"),
            ),
        )

        repo.saveMeeting(meeting1)
        repo.saveMeeting(meeting2)

        val alphaMeetings = repo.getMeetingsForParticipant("agent-alpha").getOrNull()
        assertNotNull(alphaMeetings)
        assertEquals(2, alphaMeetings.size)

        val betaMeetings = repo.getMeetingsForParticipant("agent-beta").getOrNull()
        assertNotNull(betaMeetings)
        assertEquals(1, betaMeetings.size)
    } }

    @Test
    fun `delete meeting removes all related records`() { runBlocking {
        val meeting = createCompletedMeeting()
        repo.saveMeeting(meeting)

        // Verify meeting exists
        assertNotNull(repo.getMeeting(meeting.id).getOrNull())

        // Delete meeting
        val result = repo.deleteMeeting(meeting.id)
        assertTrue(result.isSuccess)

        // Verify meeting and related data are gone
        assertNull(repo.getMeeting(meeting.id).getOrNull())

        // Directly check database tables
        val database = Database.Companion(driver)
        val queries = database.meetingStoreQueries
        assertTrue(queries.getParticipantsByMeetingId(meeting.id).executeAsList().isEmpty())
        assertTrue(queries.getAgendaItemsForMeeting(meeting.id).executeAsList().isEmpty())
        assertTrue(queries.getOutcomesForMeeting(meeting.id).executeAsList().isEmpty())
        assertTrue(queries.getAttendeesForMeeting(meeting.id).executeAsList().isEmpty())
    } }

    @Test
    fun `get nonexistent meeting returns null`() { runBlocking {
        val loaded = repo.getMeeting("nonexistent-id").getOrNull()
        assertNull(loaded)
    } }

    @Test
    fun `mapping preserves meeting type details`() { runBlocking {
        val codeReviewMeeting = Meeting(
            id = "code-review-1",
            type = MeetingType.CodeReview(
                prId = "PR-123",
                prUrl = "https://github.com/org/repo/pull/123",
                author = EventSource.Agent("agent-author"),
                requestedReviewer = AssignedTo.Agent("agent-reviewer"),
            ),
            status = MeetingStatus.Scheduled(
                scheduledFor = Clock.System.now() + 1.hours,
            ),
            invitation = MeetingInvitation(
                title = "Code Review: Feature X",
                agenda = listOf(
                    AgendaItem(id = "ai-1", title = "Review changes"),
                ),
                requiredParticipants = listOf(
                    AssignedTo.Agent("agent-author"),
                    AssignedTo.Agent("agent-reviewer"),
                ),
            ),
        )

        repo.saveMeeting(codeReviewMeeting)

        val loaded = repo.getMeeting(codeReviewMeeting.id).getOrNull()
        assertNotNull(loaded)
        assertIs<MeetingType.CodeReview>(loaded.type)

        assertEquals("PR-123", loaded.type.prId)
        assertEquals("https://github.com/org/repo/pull/123", loaded.type.prUrl)
        assertIs<EventSource.Agent>(loaded.type.author)
        assertEquals("agent-author", loaded.type.author.agentId)
    } }

    @Test
    fun `mapping preserves agenda item status`() {
        runBlocking {
            val meeting = Meeting(
                id = "meeting-with-status",
                type = MeetingType.AdHoc(reason = "Quick sync"),
                status = MeetingStatus.Scheduled(scheduledFor = Clock.System.now() + 1.hours),
                invitation = MeetingInvitation(
                    title = "Quick Sync",
                    agenda = listOf(
                        AgendaItem(
                            id = "ai-pending",
                            title = "Pending item",
                            status = TaskStatus.Pending,
                        ),
                        AgendaItem(
                            id = "ai-completed",
                            title = "Completed item",
                            status = TaskStatus.Completed(
                                completedAt = Clock.System.now(),
                                completedBy = EventSource.Agent("agent-alpha"),
                            ),
                        ),
                        AgendaItem(
                            id = "ai-blocked",
                            title = "Blocked item",
                            status = TaskStatus.Blocked(reason = "External dependency"),
                        ),
                    ),
                    requiredParticipants = listOf(AssignedTo.Agent("agent-alpha")),
                ),
            )

            repo.saveMeeting(meeting)

            val loaded = repo.getMeeting(meeting.id).getOrNull()
            assertNotNull(loaded)
            assertEquals(3, loaded.invitation.agenda.size)

            val pendingItem = loaded.invitation.agenda[0]
            assertIs<TaskStatus.Pending>(pendingItem.status)

            val completedItem = loaded.invitation.agenda[1]
            assertIs<TaskStatus.Completed>(completedItem.status)

            val blockedItem = loaded.invitation.agenda[2]
            assertIs<TaskStatus.Blocked>(blockedItem.status)
        }
    }

    @Test
    fun `multiple outcomes are preserved correctly`() { runBlocking {
        val outcomes = listOf(
            MeetingOutcome.DecisionMade(
                id = "decision-1",
                description = "Proceed with plan A",
                decidedBy = EventSource.Agent("agent-alpha"),
            ),
            MeetingOutcome.ActionItem(
                id = "action-1",
                assignedTo = AssignedTo.Agent("agent-beta"),
                description = "Implement feature",
                dueBy = Clock.System.now() + 24.hours,
            ),
            MeetingOutcome.BlockerRaised(
                id = "blocker-1",
                description = "API unavailable",
                raisedBy = EventSource.Agent("agent-alpha"),
            ),
        )

        val meeting = createCompletedMeeting(
            outcomes = outcomes,
        )

        repo.saveMeeting(meeting)

        val loaded = repo.getMeeting(meeting.id).getOrNull()
        assertNotNull(loaded)

        val status = loaded.status as MeetingStatus.Completed
        assertNotNull(status.outcomes)
        assertEquals(3, status.outcomes.size)

        // Verify each outcome type is correct
        assertTrue(status.outcomes.any { it is MeetingOutcome.DecisionMade })
        assertTrue(status.outcomes.any { it is MeetingOutcome.ActionItem })
        assertTrue(status.outcomes.any { it is MeetingOutcome.BlockerRaised })
    } }
}
