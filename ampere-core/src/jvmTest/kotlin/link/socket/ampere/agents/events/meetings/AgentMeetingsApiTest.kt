package link.socket.ampere.agents.events.meetings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

class AgentMeetingsApiTest {

    private val stubAgentId = "agent-A"
    private val stubAgentId2 = "agent-B"

    private val stubParticipant = AssignedTo.Agent(stubAgentId)
    private val stubParticipant2 = AssignedTo.Agent(stubAgentId2)

    private val json = DEFAULT_JSON
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventSerialBus: EventSerialBus

    private lateinit var meetingRepository: MeetingRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var meetingOrchestrator: MeetingOrchestrator

    private lateinit var messageApi: AgentMessageApi
    private lateinit var agentMeetingsApiFactory: AgentMeetingsApiFactory

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)

        eventSerialBus = eventSerialBusFactory.create()
        meetingRepository = MeetingRepository(json, scope, database)
        messageRepository = MessageRepository(json, scope, database)
        messageApi = AgentMessageApi(stubAgentId, messageRepository, eventSerialBus)

        meetingOrchestrator = MeetingOrchestrator(
            repository = meetingRepository,
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
        )

        agentMeetingsApiFactory = AgentMeetingsApiFactory(
            meetingOrchestrator = meetingOrchestrator,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `create meeting using builder`() {
        val api = agentMeetingsApiFactory.create(stubAgentId)
        val received = mutableListOf<Meeting>()

        val meetingType = MeetingType.SprintPlanning(
            teamId = "team-1",
            sprintId = "sprint-1",
        )

        val now = Clock.System.now()

        scope.launch {
            api.createMeeting(
                onMeetingCreated = { meeting ->
                    received.add(meeting)
                },
            ) {
                ofType(meetingType)
                withTitle("Meeting")
                addAgendaItem(
                    topic = "Agenda Item 1",
                    assignedTo = stubParticipant,
                )
                addParticipant(stubParticipant)
                addOptionalParticipant(stubParticipant2)
                scheduledFor(now)
            }

            assert(received.isNotEmpty())

            val meeting = received.first()
            assertEquals(meetingType, meeting.type)
            assertEquals("Meeting", meeting.invitation.title)
            assertEquals(1, meeting.invitation.agenda.size)
            assertEquals(stubParticipant, meeting.invitation.agenda.first().assignedTo)
            assertEquals(stubParticipant, meeting.invitation.requiredParticipants.first())
            assertEquals(stubParticipant2, meeting.invitation.optionalParticipants?.last())
            assertEquals(now, meeting.status.scheduledFor)
        }
    }
}
