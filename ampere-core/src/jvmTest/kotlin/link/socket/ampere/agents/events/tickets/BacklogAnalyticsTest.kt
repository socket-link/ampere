package link.socket.ampere.agents.events.tickets

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.ProductAgent
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.escalation.Escalation
import link.socket.ampere.agents.events.meetings.MeetingOrchestrator
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.meetings.MeetingSchedulingService
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.stubProductManagerAgent

class BacklogAnalyticsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    private lateinit var messageRepository: MessageRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var ticketRepository: TicketRepository

    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var meetingOrchestrator: MeetingOrchestrator
    private lateinit var ticketOrchestrator: TicketOrchestrator

    private lateinit var productAgent: ProductAgent

    private val testScope = CoroutineScope(Dispatchers.Default)
    private val stubOrchestratorAgentId: AgentId = "orchestrator-agent"
    private val stubPmAgentId: AgentId = "pm-agent"
    private val stubDevAgent1Id: AgentId = "dev-agent-1"
    private val stubDevAgent2Id: AgentId = "dev-agent-2"

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database.Companion(driver)

        messageRepository = MessageRepository(DEFAULT_JSON, testScope, database)
        meetingRepository = MeetingRepository(DEFAULT_JSON, testScope, database)
        ticketRepository = TicketRepository(database)

        eventSerialBus = EventSerialBus(testScope)
        messageApi = AgentMessageApi(stubOrchestratorAgentId, messageRepository, eventSerialBus)

        meetingOrchestrator = MeetingOrchestrator(
            repository = meetingRepository,
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
        )

        // Create a MeetingSchedulingService that delegates to the meetingOrchestrator
        val meetingSchedulingService = MeetingSchedulingService { meeting, scheduledBy ->
            meetingOrchestrator.scheduleMeeting(meeting, scheduledBy)
        }

        ticketOrchestrator = TicketOrchestrator(
            ticketRepository = ticketRepository,
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
            meetingSchedulingService = meetingSchedulingService,
        )

        productAgent = stubProductManagerAgent(
            ticketOrchestrator = ticketOrchestrator,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== BacklogSummary Tests ====================

    @Test
    fun `getBacklogSummary returns zeros with no tickets`() {
        runBlocking {
            val result = ticketOrchestrator.getBacklogSummary()

            assertTrue(result.isSuccess)
            val summary = result.getOrNull()!!

            assertEquals(0, summary.totalTickets)
            assertTrue(summary.ticketsByStatus.isEmpty())
            assertTrue(summary.ticketsByPriority.isEmpty())
            assertTrue(summary.ticketsByType.isEmpty())
            assertEquals(0, summary.blockedCount)
            assertEquals(0, summary.overdueCount)
        }
    }

    @Test
    fun `getBacklogSummary returns correct counts by status`() {
        runBlocking {
            // Create tickets in different statuses
            // BACKLOG tickets
            createTestTicket("Backlog 1", TicketType.FEATURE, TicketPriority.HIGH)
            createTestTicket("Backlog 2", TicketType.BUG, TicketPriority.MEDIUM)

            // READY ticket
            val readyTicket = createTestTicket("Ready 1", TicketType.TASK, TicketPriority.LOW)
            ticketOrchestrator.transitionTicketStatus(readyTicket.id, TicketStatus.Ready, stubPmAgentId)

            // IN_PROGRESS ticket
            val inProgressTicket = createTestTicket("In Progress 1", TicketType.SPIKE, TicketPriority.HIGH)
            ticketOrchestrator.transitionTicketStatus(inProgressTicket.id, TicketStatus.Ready, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(inProgressTicket.id, TicketStatus.InProgress, stubPmAgentId)

            val result = ticketOrchestrator.getBacklogSummary()

            assertTrue(result.isSuccess)
            val summary = result.getOrNull()!!

            assertEquals(4, summary.totalTickets)
            assertEquals(2, summary.ticketsByStatus[TicketStatus.Backlog])
            assertEquals(1, summary.ticketsByStatus[TicketStatus.Ready])
            assertEquals(1, summary.ticketsByStatus[TicketStatus.InProgress])
        }
    }

    @Test
    fun `getBacklogSummary returns correct counts by priority`() {
        runBlocking {
            createTestTicket("Critical", TicketType.BUG, TicketPriority.CRITICAL)
            createTestTicket("High 1", TicketType.FEATURE, TicketPriority.HIGH)
            createTestTicket("High 2", TicketType.TASK, TicketPriority.HIGH)
            createTestTicket("Medium", TicketType.SPIKE, TicketPriority.MEDIUM)
            createTestTicket("Low", TicketType.TASK, TicketPriority.LOW)

            val result = ticketOrchestrator.getBacklogSummary()

            assertTrue(result.isSuccess)
            val summary = result.getOrNull()!!

            assertEquals(5, summary.totalTickets)
            assertEquals(1, summary.ticketsByPriority[TicketPriority.CRITICAL])
            assertEquals(2, summary.ticketsByPriority[TicketPriority.HIGH])
            assertEquals(1, summary.ticketsByPriority[TicketPriority.MEDIUM])
            assertEquals(1, summary.ticketsByPriority[TicketPriority.LOW])
        }
    }

    @Test
    fun `getBacklogSummary returns correct counts by type`() {
        runBlocking {
            createTestTicket("Feature 1", TicketType.FEATURE, TicketPriority.HIGH)
            createTestTicket("Feature 2", TicketType.FEATURE, TicketPriority.MEDIUM)
            createTestTicket("Bug 1", TicketType.BUG, TicketPriority.CRITICAL)
            createTestTicket("Task 1", TicketType.TASK, TicketPriority.LOW)
            createTestTicket("Spike 1", TicketType.SPIKE, TicketPriority.MEDIUM)

            val result = ticketOrchestrator.getBacklogSummary()

            assertTrue(result.isSuccess)
            val summary = result.getOrNull()!!

            assertEquals(5, summary.totalTickets)
            assertEquals(2, summary.ticketsByType[TicketType.FEATURE])
            assertEquals(1, summary.ticketsByType[TicketType.BUG])
            assertEquals(1, summary.ticketsByType[TicketType.TASK])
            assertEquals(1, summary.ticketsByType[TicketType.SPIKE])
        }
    }

    @Test
    fun `getBacklogSummary returns correct blocked count`() {
        runBlocking {
            // Create and block some tickets
            val ticket1 = createTestTicket("Blocked 1", TicketType.BUG, TicketPriority.HIGH)
            ticketOrchestrator.assignTicket(ticket1.id, stubDevAgent1Id, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket1.id, TicketStatus.Ready, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket1.id, TicketStatus.InProgress, stubPmAgentId)
            ticketOrchestrator.blockTicket(
                ticketId = ticket1.id,
                blockingReason = "Waiting for API",
                escalationType = Escalation.Budget.CostApproval,
                reportedByAgentId = stubDevAgent1Id,
                assignedToAgentId = stubDevAgent1Id,
            )

            val ticket2 = createTestTicket("Blocked 2", TicketType.FEATURE, TicketPriority.CRITICAL)
            ticketOrchestrator.assignTicket(ticket2.id, stubDevAgent1Id, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket2.id, TicketStatus.Ready, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket2.id, TicketStatus.InProgress, stubPmAgentId)
            ticketOrchestrator.blockTicket(
                ticketId = ticket2.id,
                blockingReason = "Waiting for external",
                escalationType = Escalation.Budget.CostApproval,
                reportedByAgentId = stubDevAgent1Id,
                assignedToAgentId = stubDevAgent1Id,
            )

            // Not blocked
            createTestTicket("Not Blocked", TicketType.TASK, TicketPriority.LOW)

            val result = ticketOrchestrator.getBacklogSummary()

            assertTrue(result.isSuccess)
            val summary = result.getOrNull()!!

            assertEquals(3, summary.totalTickets)
            assertEquals(2, summary.blockedCount)
        }
    }

    @Test
    fun `getBacklogSummary returns correct overdue count`() {
        runBlocking {
            val now = Clock.System.now()

            // Create overdue ticket (due in past)
            val overdueTicket = createTestTicketWithDueDate(
                "Overdue",
                TicketType.BUG,
                TicketPriority.HIGH,
                now - 1.days,
            )

            // Create ticket due in future (not overdue)
            createTestTicketWithDueDate(
                "Future",
                TicketType.FEATURE,
                TicketPriority.MEDIUM,
                now + 7.days,
            )

            // Create ticket with no due date
            createTestTicket("No Due Date", TicketType.TASK, TicketPriority.LOW)

            // Complete ticket that was overdue (should not count as overdue)
            val completedOverdue = createTestTicketWithDueDate(
                "Completed Overdue",
                TicketType.TASK,
                TicketPriority.LOW,
                now - 2.days,
            )
            ticketOrchestrator.transitionTicketStatus(completedOverdue.id, TicketStatus.Done, stubPmAgentId)

            val result = ticketOrchestrator.getBacklogSummary()

            assertTrue(result.isSuccess)
            val summary = result.getOrNull()!!

            assertEquals(4, summary.totalTickets)
            assertEquals(1, summary.overdueCount)
        }
    }

    // ==================== AgentWorkload Tests ====================

    @Test
    fun `getAgentWorkload returns empty workload for agent with no tickets`() {
        runBlocking {
            val result = ticketOrchestrator.getAgentWorkload(stubDevAgent1Id)

            assertTrue(result.isSuccess)
            val workload = result.getOrNull()!!

            assertEquals(stubDevAgent1Id, workload.agentId)
            assertTrue(workload.assignedTickets.isEmpty())
            assertEquals(0, workload.inProgressCount)
            assertEquals(0, workload.blockedCount)
            assertEquals(0, workload.completedCount)
            assertEquals(0, workload.activeCount)
        }
    }

    @Test
    fun `getAgentWorkload returns correct counts for assigned agent`() {
        runBlocking {
            // Create and assign tickets to dev-agent-1
            val ticket1 = createTestTicket("Ticket 1", TicketType.FEATURE, TicketPriority.HIGH)
            ticketOrchestrator.assignTicket(ticket1.id, stubDevAgent1Id, stubPmAgentId)

            val ticket2 = createTestTicket("Ticket 2", TicketType.BUG, TicketPriority.CRITICAL)
            ticketOrchestrator.assignTicket(ticket2.id, stubDevAgent1Id, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket2.id, TicketStatus.Ready, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket2.id, TicketStatus.InProgress, stubPmAgentId)

            val ticket3 = createTestTicket("Ticket 3", TicketType.TASK, TicketPriority.LOW)
            ticketOrchestrator.assignTicket(ticket3.id, stubDevAgent1Id, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket3.id, TicketStatus.Ready, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket3.id, TicketStatus.InProgress, stubPmAgentId)
            ticketOrchestrator.blockTicket(
                ticketId = ticket3.id,
                blockingReason = "Waiting",
                escalationType = Escalation.Budget.CostApproval,
                reportedByAgentId = stubDevAgent1Id,
                assignedToAgentId = stubDevAgent1Id,
            )

            val ticket4 = createTestTicket("Ticket 4", TicketType.SPIKE, TicketPriority.MEDIUM)
            ticketOrchestrator.assignTicket(ticket4.id, stubDevAgent1Id, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket4.id, TicketStatus.Done, stubPmAgentId)

            // Assign ticket to different agent (should not count)
            val ticket5 = createTestTicket("Other Agent", TicketType.TASK, TicketPriority.LOW)
            ticketOrchestrator.assignTicket(ticket5.id, stubDevAgent2Id, stubPmAgentId)

            val result = ticketOrchestrator.getAgentWorkload(stubDevAgent1Id)

            assertTrue(result.isSuccess)
            val workload = result.getOrNull()!!

            assertEquals(stubDevAgent1Id, workload.agentId)
            assertEquals(4, workload.assignedTickets.size)
            assertEquals(1, workload.inProgressCount)
            assertEquals(1, workload.blockedCount)
            assertEquals(1, workload.completedCount)
            assertEquals(3, workload.activeCount) // 4 total - 1 completed
        }
    }

    @Test
    fun `getAgentWorkload correctly distinguishes between agents`() {
        runBlocking {
            // Assign to agent 1
            val ticket1 = createTestTicket("Agent1 Ticket", TicketType.FEATURE, TicketPriority.HIGH)
            ticketOrchestrator.assignTicket(ticket1.id, stubDevAgent1Id, stubPmAgentId)

            // Assign to agent 2
            val ticket2 = createTestTicket("Agent2 Ticket 1", TicketType.BUG, TicketPriority.MEDIUM)
            ticketOrchestrator.assignTicket(ticket2.id, stubDevAgent2Id, stubPmAgentId)

            val ticket3 = createTestTicket("Agent2 Ticket 2", TicketType.TASK, TicketPriority.LOW)
            ticketOrchestrator.assignTicket(ticket3.id, stubDevAgent2Id, stubPmAgentId)

            val workload1 = ticketOrchestrator.getAgentWorkload(stubDevAgent1Id).getOrNull()!!
            val workload2 = ticketOrchestrator.getAgentWorkload(stubDevAgent2Id).getOrNull()!!

            assertEquals(1, workload1.assignedTickets.size)
            assertEquals(2, workload2.assignedTickets.size)
        }
    }

    // ==================== UpcomingDeadlines Tests ====================

    @Test
    fun `getUpcomingDeadlines returns empty list when no tickets have due dates`() {
        runBlocking {
            createTestTicket("No Due Date 1", TicketType.FEATURE, TicketPriority.HIGH)
            createTestTicket("No Due Date 2", TicketType.BUG, TicketPriority.MEDIUM)

            val result = ticketOrchestrator.getUpcomingDeadlines(7)

            assertTrue(result.isSuccess)
            val deadlines = result.getOrNull()!!

            assertTrue(deadlines.isEmpty())
        }
    }

    @Test
    fun `getUpcomingDeadlines returns only tickets within specified days`() {
        runBlocking {
            val now = Clock.System.now()

            // Within 7 days
            val ticket1 = createTestTicketWithDueDate(
                "Due in 3 days",
                TicketType.FEATURE,
                TicketPriority.HIGH,
                now + 3.days,
            )

            val ticket2 = createTestTicketWithDueDate(
                "Due in 5 days",
                TicketType.BUG,
                TicketPriority.MEDIUM,
                now + 5.days,
            )

            // Outside 7 days
            createTestTicketWithDueDate(
                "Due in 10 days",
                TicketType.TASK,
                TicketPriority.LOW,
                now + 10.days,
            )

            // Past due (should not be included)
            createTestTicketWithDueDate(
                "Overdue",
                TicketType.SPIKE,
                TicketPriority.CRITICAL,
                now - 1.days,
            )

            val result = ticketOrchestrator.getUpcomingDeadlines(7)

            assertTrue(result.isSuccess)
            val deadlines = result.getOrNull()!!

            assertEquals(2, deadlines.size)
            assertTrue(deadlines.any { it.id == ticket1.id })
            assertTrue(deadlines.any { it.id == ticket2.id })
        }
    }

    @Test
    fun `getUpcomingDeadlines returns tickets sorted by due date ascending`() {
        runBlocking {
            val now = Clock.System.now()

            createTestTicketWithDueDate(
                "Due in 5 days",
                TicketType.FEATURE,
                TicketPriority.HIGH,
                now + 5.days,
            )

            createTestTicketWithDueDate(
                "Due in 1 day",
                TicketType.BUG,
                TicketPriority.MEDIUM,
                now + 1.days,
            )

            createTestTicketWithDueDate(
                "Due in 3 days",
                TicketType.TASK,
                TicketPriority.LOW,
                now + 3.days,
            )

            val result = ticketOrchestrator.getUpcomingDeadlines(7)

            assertTrue(result.isSuccess)
            val deadlines = result.getOrNull()!!

            assertEquals(3, deadlines.size)
            assertEquals("Due in 1 day", deadlines[0].title)
            assertEquals("Due in 3 days", deadlines[1].title)
            assertEquals("Due in 5 days", deadlines[2].title)
        }
    }

    @Test
    fun `getUpcomingDeadlines excludes completed tickets`() {
        runBlocking {
            val now = Clock.System.now()

            // Active ticket with due date
            val activeTicket = createTestTicketWithDueDate(
                "Active",
                TicketType.FEATURE,
                TicketPriority.HIGH,
                now + 3.days,
            )

            // Completed ticket with due date (should not be included)
            val completedTicket = createTestTicketWithDueDate(
                "Completed",
                TicketType.BUG,
                TicketPriority.MEDIUM,
                now + 2.days,
            )
            ticketOrchestrator.transitionTicketStatus(completedTicket.id, TicketStatus.Done, stubPmAgentId)

            val result = ticketOrchestrator.getUpcomingDeadlines(7)

            assertTrue(result.isSuccess)
            val deadlines = result.getOrNull()!!

            assertEquals(1, deadlines.size)
            assertEquals(activeTicket.id, deadlines[0].id)
        }
    }

    @Test
    fun `getUpcomingDeadlines respects daysAhead parameter`() {
        runBlocking {
            val now = Clock.System.now()

            createTestTicketWithDueDate(
                "Due in 2 days",
                TicketType.FEATURE,
                TicketPriority.HIGH,
                now + 2.days,
            )

            createTestTicketWithDueDate(
                "Due in 4 days",
                TicketType.BUG,
                TicketPriority.MEDIUM,
                now + 4.days,
            )

            // Test with 3 days ahead
            val result = ticketOrchestrator.getUpcomingDeadlines(3)

            assertTrue(result.isSuccess)
            val deadlines = result.getOrNull()!!

            assertEquals(1, deadlines.size)
            assertEquals("Due in 2 days", deadlines[0].title)
        }
    }

    // ==================== ProductManagerAgent Tests ====================

    @Test
    fun `PM agent perceive returns empty state with no tickets`() {
        runBlocking {
            val state = productAgent.getCurrentState()

            assertEquals(0, state.backlogSummary.totalTickets)
            assertTrue(state.agentWorkloads.isEmpty())
            assertTrue(state.upcomingDeadlines.isEmpty())
            assertTrue(state.blockedTickets.isEmpty())
            assertTrue(state.overdueTickets.isEmpty())
        }
    }

    @Test
    fun `PM agent perceive returns backlog summary`() {
        runBlocking {
            // Create various tickets
            createTestTicket("Feature", TicketType.FEATURE, TicketPriority.HIGH)
            createTestTicket("Bug", TicketType.BUG, TicketPriority.CRITICAL)
            createTestTicket("Task", TicketType.TASK, TicketPriority.LOW)

            val state = productAgent.getCurrentState()

            assertEquals(3, state.backlogSummary.totalTickets)
            assertEquals(3, state.backlogSummary.ticketsByStatus[TicketStatus.Backlog])
        }
    }

    @Test
    fun `PM agent perceive returns agent workloads when agents specified`() {
        runBlocking {
            // Assign tickets
            val ticket1 = createTestTicket("Agent1 Work", TicketType.FEATURE, TicketPriority.HIGH)
            ticketOrchestrator.assignTicket(ticket1.id, stubDevAgent1Id, stubPmAgentId)

            val ticket2 = createTestTicket("Agent2 Work", TicketType.BUG, TicketPriority.MEDIUM)
            ticketOrchestrator.assignTicket(ticket2.id, stubDevAgent2Id, stubPmAgentId)

            val state = productAgent.getCurrentState()

            assertEquals(2, state.agentWorkloads.size)
            assertNotNull(state.agentWorkloads[stubDevAgent1Id])
            assertNotNull(state.agentWorkloads[stubDevAgent2Id])
            assertEquals(1, state.agentWorkloads[stubDevAgent1Id]!!.assignedTickets.size)
            assertEquals(1, state.agentWorkloads[stubDevAgent2Id]!!.assignedTickets.size)
        }
    }

    @Test
    fun `PM agent perceive includes blocked tickets`() {
        runBlocking {
            // Create and block a ticket
            val blockedTicket = createTestTicket("Blocked", TicketType.BUG, TicketPriority.HIGH)
            ticketOrchestrator.assignTicket(blockedTicket.id, stubDevAgent1Id, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(blockedTicket.id, TicketStatus.Ready, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(blockedTicket.id, TicketStatus.InProgress, stubPmAgentId)
            ticketOrchestrator.blockTicket(
                ticketId = blockedTicket.id,
                blockingReason = "Waiting",
                escalationType = Escalation.Budget.CostApproval,
                reportedByAgentId = stubDevAgent1Id,
                assignedToAgentId = stubDevAgent1Id,
            )

            val state = productAgent.getCurrentState()

            assertEquals(1, state.blockedTickets.size)
            assertEquals(blockedTicket.id, state.blockedTickets[0].id)
        }
    }

    @Test
    fun `PM agent perceive includes overdue tickets`() {
        runBlocking {
            val now = Clock.System.now()

            // Create overdue ticket
            val overdueTicket = createTestTicketWithDueDate(
                "Overdue",
                TicketType.FEATURE,
                TicketPriority.HIGH,
                now - 1.days,
            )
            ticketOrchestrator.assignTicket(overdueTicket.id, stubDevAgent1Id, stubPmAgentId)

            val state = productAgent.getCurrentState()

            assertEquals(1, state.overdueTickets.size)
            assertEquals(overdueTicket.id, state.overdueTickets[0].id)
        }
    }

    @Test
    fun `PM agent perceive includes upcoming deadlines`() {
        runBlocking {
            val now = Clock.System.now()

            createTestTicketWithDueDate(
                "Due Soon",
                TicketType.FEATURE,
                TicketPriority.HIGH,
                now + 3.days,
            )

            val state = productAgent.getCurrentState()

            assertEquals(1, state.upcomingDeadlines.size)
            assertEquals("Due Soon", state.upcomingDeadlines[0].title)
        }
    }

    @Test
    fun `PM agent perceiveAsText returns formatted text`() {
        runBlocking {
            // Create test data
            createTestTicket("Test Feature", TicketType.FEATURE, TicketPriority.HIGH)

            val state = productAgent.getCurrentState()
            val perception = productAgent.perceiveState(state)
            val titles = perception.ideas.map { it.name }

            assertTrue(titles.contains("PM Agent Perception State"))
            assertTrue(titles.contains("Backlog Summary"))
            assertTrue(titles.contains("Total Tickets: 1"))
        }
    }

    @Test
    fun `PM perception text highlights blocked tickets`() {
        runBlocking {
            // Create and block a ticket
            val blockedTicket = createTestTicket("Blocked Feature", TicketType.FEATURE, TicketPriority.HIGH)
            ticketOrchestrator.assignTicket(blockedTicket.id, stubDevAgent1Id, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(blockedTicket.id, TicketStatus.Ready, stubPmAgentId)
            ticketOrchestrator.transitionTicketStatus(blockedTicket.id, TicketStatus.InProgress, stubPmAgentId)
            ticketOrchestrator.blockTicket(
                ticketId = blockedTicket.id,
                blockingReason = "Test",
                escalationType = Escalation.Budget.CostApproval,
                reportedByAgentId = stubDevAgent1Id,
                assignedToAgentId = stubDevAgent1Id,
            )

            val state = productAgent.getCurrentState()
            val perception = productAgent.perceiveState(state)
            val titles = perception.ideas.map { it.name }

            assertTrue(titles.contains("BLOCKED TICKETS"))
            assertTrue(titles.contains("Blocked Feature"))
        }
    }

    @Test
    fun `BacklogSummary toPerceptionText formats correctly`() {
        val summary = BacklogSummary(
            totalTickets = 10,
            ticketsByStatus = mapOf(
                TicketStatus.Backlog to 5,
                TicketStatus.InProgress to 3,
                TicketStatus.Done to 2,
            ),
            ticketsByPriority = mapOf(
                TicketPriority.HIGH to 4,
                TicketPriority.MEDIUM to 6,
            ),
            ticketsByType = mapOf(
                TicketType.FEATURE to 5,
                TicketType.BUG to 5,
            ),
            blockedCount = 1,
            overdueCount = 2,
        )

        val text = summary.toPerceptionText()

        assertTrue(text.contains("Backlog Summary"))
        assertTrue(text.contains("Total Tickets: 10"))
        assertTrue(text.contains("Blocked: 1"))
        assertTrue(text.contains("Overdue: 2"))
    }

    @Test
    fun `AgentWorkload toPerceptionText formats correctly`() {
        val now = Clock.System.now()
        val workload = AgentWorkload(
            agentId = stubDevAgent1Id,
            assignedTickets = listOf(
                Ticket(
                    id = "test-1",
                    title = "Test Ticket",
                    description = "Description",
                    type = TicketType.FEATURE,
                    priority = TicketPriority.HIGH,
                    status = TicketStatus.InProgress,
                    assignedAgentId = stubDevAgent1Id,
                    createdByAgentId = stubPmAgentId,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            inProgressCount = 1,
            blockedCount = 0,
            completedCount = 0,
        )

        val text = workload.toPerceptionText()

        assertTrue(text.contains("Agent Workload: $stubDevAgent1Id"))
        assertTrue(text.contains("Total Assigned: 1"))
        assertTrue(text.contains("In Progress: 1"))
        assertTrue(text.contains("Test Ticket"))
    }

    // ==================== Helper Methods ====================

    private suspend fun createTestTicket(
        title: String,
        type: TicketType,
        priority: TicketPriority,
    ): Ticket {
        val result = ticketOrchestrator.createTicket(
            title = title,
            description = "Test description for $title",
            type = type,
            priority = priority,
            createdByAgentId = stubPmAgentId,
        )
        return result.getOrNull()!!.first
    }

    private suspend fun createTestTicketWithDueDate(
        title: String,
        type: TicketType,
        priority: TicketPriority,
        dueDate: kotlinx.datetime.Instant,
    ): Ticket {
        val ticket = createTestTicket(title, type, priority)
        ticketRepository.updateTicketDetails(
            ticketId = ticket.id,
            dueDate = dueDate,
        )
        return ticketRepository.getTicket(ticket.id).getOrNull()!!
    }
}
