package link.socket.ampere.agents.events.meetings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.db.Database
import link.socket.ampere.db.meetings.MeetingStoreQueries

class MeetingSchemaTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var queries: MeetingStoreQueries

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)
        queries = database.meetingStoreQueries
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `insert and retrieve meeting by id`() {
        val meetingId = "meeting-1"
        val now = Clock.System.now().toEpochMilliseconds()

        queries.insertMeeting(
            id = meetingId,
            title = "Daily Standup",
            typeJson = """{"type":"Standup","teamId":"team-1","sprintId":"sprint-1"}""",
            statusType = "SCHEDULED",
            statusJson = """{"scheduledFor":"2024-01-15T09:00:00Z"}""",
            scheduledTime = now,
            channelId = null,
            threadId = null,
            invitationJson = """{"title":"Daily Standup","agenda":[],"requiredParticipants":[]}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        val meeting = queries.getMeetingById(meetingId).executeAsOneOrNull()
        assertNotNull(meeting)
        assertEquals(meetingId, meeting.id)
        assertEquals("Daily Standup", meeting.title)
        assertEquals("SCHEDULED", meeting.statusType)
    }

    @Test
    fun `get all scheduled meetings`() {
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert scheduled meeting
        queries.insertMeeting(
            id = "meeting-scheduled",
            title = "Sprint Planning",
            typeJson = """{"type":"SprintPlanning"}""",
            statusType = "SCHEDULED",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = null,
            threadId = null,
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Insert completed meeting
        queries.insertMeeting(
            id = "meeting-completed",
            title = "Retro",
            typeJson = """{"type":"AdHoc"}""",
            statusType = "COMPLETED",
            statusJson = """{}""",
            scheduledTime = now - 1000,
            channelId = "ch-1",
            threadId = "th-1",
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now - 1000,
            updatedAt = now,
        )

        val scheduled = queries.getScheduledMeetings().executeAsList()
        assertEquals(1, scheduled.size)
        assertEquals("meeting-scheduled", scheduled.first().id)
    }

    @Test
    fun `insert and retrieve participants`() {
        val meetingId = "meeting-2"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert meeting first
        queries.insertMeeting(
            id = meetingId,
            title = "Code Review",
            typeJson = """{"type":"CodeReview"}""",
            statusType = "SCHEDULED",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = null,
            threadId = null,
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Insert participants
        queries.insertParticipant(meetingId, "agent-alpha", "AGENT", 1)
        queries.insertParticipant(meetingId, "agent-beta", "AGENT", 1)
        queries.insertParticipant(meetingId, "human-1", "HUMAN", 0)

        val all = queries.getParticipantsByMeetingId(meetingId).executeAsList()
        assertEquals(3, all.size)

        val required = queries.getRequiredParticipants(meetingId).executeAsList()
        assertEquals(2, required.size)

        val optional = queries.getOptionalParticipants(meetingId).executeAsList()
        assertEquals(1, optional.size)
        assertEquals("human-1", optional.first().participantId)
    }

    @Test
    fun `insert and retrieve agenda items`() {
        val meetingId = "meeting-3"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert meeting first
        queries.insertMeeting(
            id = meetingId,
            title = "Sprint Review",
            typeJson = """{}""",
            statusType = "IN_PROGRESS",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = "ch-1",
            threadId = "th-1",
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Insert agenda items
        queries.insertAgendaItem(
            id = "ai-1",
            meetingId = meetingId,
            topic = "Yesterday updates",
            assignedTo = "agent-alpha",
            assignedToType = "AGENT",
            status = "PENDING",
            statusPayload = null,
            orderIndex = 0,
        )
        queries.insertAgendaItem(
            id = "ai-2",
            meetingId = meetingId,
            topic = "Today plans",
            assignedTo = "agent-beta",
            assignedToType = "AGENT",
            status = "PENDING",
            statusPayload = null,
            orderIndex = 1,
        )
        queries.insertAgendaItem(
            id = "ai-3",
            meetingId = meetingId,
            topic = "Blockers",
            assignedTo = null,
            assignedToType = null,
            status = "PENDING",
            statusPayload = null,
            orderIndex = 2,
        )

        val items = queries.getAgendaItemsForMeeting(meetingId).executeAsList()
        assertEquals(3, items.size)
        assertEquals("Yesterday updates", items[0].topic)
        assertEquals("Today plans", items[1].topic)
        assertEquals("Blockers", items[2].topic)

        // Verify orderIndex sorting
        assertEquals(0, items[0].orderIndex)
        assertEquals(1, items[1].orderIndex)
        assertEquals(2, items[2].orderIndex)
    }

    @Test
    fun `update agenda item status`() {
        val meetingId = "meeting-4"
        val agendaItemId = "ai-4"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert meeting
        queries.insertMeeting(
            id = meetingId,
            title = "Test",
            typeJson = """{}""",
            statusType = "IN_PROGRESS",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = null,
            threadId = null,
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Insert agenda item
        queries.insertAgendaItem(
            id = agendaItemId,
            meetingId = meetingId,
            topic = "Review PR",
            assignedTo = "agent-alpha",
            assignedToType = "AGENT",
            status = "PENDING",
            statusPayload = null,
            orderIndex = 0,
        )

        // Update status
        queries.updateAgendaItemStatus(
            status = "COMPLETED",
            statusPayload = """{"completedAt":"2024-01-15T10:00:00Z"}""",
            id = agendaItemId,
        )

        val updated = queries.getAgendaItemById(agendaItemId).executeAsOneOrNull()
        assertNotNull(updated)
        assertEquals("COMPLETED", updated.status)
        assertNotNull(updated.statusPayload)
    }

    @Test
    fun `insert and retrieve outcomes`() {
        val meetingId = "meeting-5"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert meeting
        queries.insertMeeting(
            id = meetingId,
            title = "Planning",
            typeJson = """{}""",
            statusType = "COMPLETED",
            statusJson = """{}""",
            scheduledTime = now - 1000,
            channelId = "ch-1",
            threadId = "th-1",
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now - 1000,
            updatedAt = now,
        )

        // Insert outcomes
        queries.insertOutcome(
            id = "out-1",
            meetingId = meetingId,
            outcomeType = "DECISION_MADE",
            description = "Proceed with refactor",
            outcomeJson = """{"decidedBy":{"type":"Agent","agentId":"agent-alpha"}}""",
            createdAt = now,
        )
        queries.insertOutcome(
            id = "out-2",
            meetingId = meetingId,
            outcomeType = "ACTION_ITEM",
            description = "Implement new API",
            outcomeJson = """{"assignedTo":{"type":"Agent","agentId":"agent-beta"}}""",
            createdAt = now + 100,
        )

        val outcomes = queries.getOutcomesForMeeting(meetingId).executeAsList()
        assertEquals(2, outcomes.size)

        val actionItems = queries.getOutcomesByType("ACTION_ITEM").executeAsList()
        assertEquals(1, actionItems.size)
        assertEquals("Implement new API", actionItems.first().description)
    }

    @Test
    fun `get action items for agent`() {
        val meetingId = "meeting-6"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert meeting
        queries.insertMeeting(
            id = meetingId,
            title = "Sprint Planning",
            typeJson = """{}""",
            statusType = "COMPLETED",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = "ch-1",
            threadId = "th-1",
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Insert action items for different agents
        queries.insertOutcome(
            id = "out-a1",
            meetingId = meetingId,
            outcomeType = "ACTION_ITEM",
            description = "Write tests",
            outcomeJson = """{"assignedTo":"agent-alpha"}""",
            createdAt = now,
        )
        queries.insertOutcome(
            id = "out-a2",
            meetingId = meetingId,
            outcomeType = "ACTION_ITEM",
            description = "Update docs",
            outcomeJson = """{"assignedTo":"agent-beta"}""",
            createdAt = now,
        )

        val alphaItems = queries.getActionItemsForAgent("agent-alpha").executeAsList()
        assertEquals(1, alphaItems.size)
        assertEquals("Write tests", alphaItems.first().description)
    }

    @Test
    fun `update meeting status`() {
        val meetingId = "meeting-7"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert meeting
        queries.insertMeeting(
            id = meetingId,
            title = "Daily Standup",
            typeJson = """{}""",
            statusType = "SCHEDULED",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = null,
            threadId = null,
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Update to in progress
        queries.updateMeetingStatus(
            statusType = "IN_PROGRESS",
            statusJson = """{"startedAt":"2024-01-15T09:00:00Z"}""",
            channelId = "ch-1",
            threadId = "th-1",
            updatedAt = now + 1000,
            id = meetingId,
        )

        val updated = queries.getMeetingById(meetingId).executeAsOneOrNull()
        assertNotNull(updated)
        assertEquals("IN_PROGRESS", updated.statusType)
        assertEquals("ch-1", updated.channelId)
        assertEquals("th-1", updated.threadId)
    }

    @Test
    fun `delete meeting and related records`() {
        val meetingId = "meeting-8"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert meeting with all related data
        queries.insertMeeting(
            id = meetingId,
            title = "Test Meeting",
            typeJson = """{}""",
            statusType = "COMPLETED",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = "ch-1",
            threadId = "th-1",
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )
        queries.insertParticipant(meetingId, "agent-1", "AGENT", 1)
        queries.insertAgendaItem("ai-del", meetingId, "Topic", null, null, "COMPLETED", null, 0)
        queries.insertOutcome("out-del", meetingId, "DECISION_MADE", "Test", """{}""", now)
        queries.insertAttendee(meetingId, "agent-1", "AGENT")

        // Verify data exists
        assertEquals(1, queries.getParticipantsByMeetingId(meetingId).executeAsList().size)
        assertEquals(1, queries.getAgendaItemsForMeeting(meetingId).executeAsList().size)
        assertEquals(1, queries.getOutcomesForMeeting(meetingId).executeAsList().size)
        assertEquals(1, queries.getAttendeesForMeeting(meetingId).executeAsList().size)

        // Delete related records first using explicit queries, then delete meeting
        queries.deleteParticipantsForMeeting(meetingId)
        queries.deleteAgendaItemsForMeeting(meetingId)
        queries.deleteOutcomesForMeeting(meetingId)
        queries.deleteAttendeesForMeeting(meetingId)
        queries.deleteMeeting(meetingId)

        // Verify deletion
        assertNull(queries.getMeetingById(meetingId).executeAsOneOrNull())
        assertTrue(queries.getParticipantsByMeetingId(meetingId).executeAsList().isEmpty())
        assertTrue(queries.getAgendaItemsForMeeting(meetingId).executeAsList().isEmpty())
        assertTrue(queries.getOutcomesForMeeting(meetingId).executeAsList().isEmpty())
        assertTrue(queries.getAttendeesForMeeting(meetingId).executeAsList().isEmpty())
    }

    @Test
    fun `get meetings for participant`() {
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert two meetings
        queries.insertMeeting(
            id = "meeting-a",
            title = "Meeting A",
            typeJson = """{}""",
            statusType = "SCHEDULED",
            statusJson = """{}""",
            scheduledTime = now + 2000,
            channelId = null,
            threadId = null,
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )
        queries.insertMeeting(
            id = "meeting-b",
            title = "Meeting B",
            typeJson = """{}""",
            statusType = "SCHEDULED",
            statusJson = """{}""",
            scheduledTime = now + 1000,
            channelId = null,
            threadId = null,
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Add agent-alpha to both meetings
        queries.insertParticipant("meeting-a", "agent-alpha", "AGENT", 1)
        queries.insertParticipant("meeting-b", "agent-alpha", "AGENT", 1)
        // Add agent-beta to only meeting-b
        queries.insertParticipant("meeting-b", "agent-beta", "AGENT", 1)

        val alphaMeetings = queries.getMeetingsForParticipant("agent-alpha").executeAsList()
        assertEquals(2, alphaMeetings.size)
        // Should be sorted by scheduledTime ASC
        assertEquals("meeting-b", alphaMeetings[0].id)
        assertEquals("meeting-a", alphaMeetings[1].id)

        val betaMeetings = queries.getMeetingsForParticipant("agent-beta").executeAsList()
        assertEquals(1, betaMeetings.size)
        assertEquals("meeting-b", betaMeetings.first().id)
    }

    @Test
    fun `insert and retrieve attendees`() {
        val meetingId = "meeting-9"
        val now = Clock.System.now().toEpochMilliseconds()

        // Insert completed meeting
        queries.insertMeeting(
            id = meetingId,
            title = "Completed Meeting",
            typeJson = """{}""",
            statusType = "COMPLETED",
            statusJson = """{}""",
            scheduledTime = now,
            channelId = "ch-1",
            threadId = "th-1",
            invitationJson = """{}""",
            triggeringEventId = null,
            createdAt = now,
            updatedAt = now,
        )

        // Insert attendees (actual people who attended)
        queries.insertAttendee(meetingId, "agent-alpha", "AGENT")
        queries.insertAttendee(meetingId, "agent-beta", "AGENT")
        queries.insertAttendee(meetingId, "human-1", "HUMAN")

        val attendees = queries.getAttendeesForMeeting(meetingId).executeAsList()
        assertEquals(3, attendees.size)
    }
}
