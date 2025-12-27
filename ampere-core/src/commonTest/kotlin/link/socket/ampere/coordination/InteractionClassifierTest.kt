package link.socket.ampere.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.subscription.EventSubscription

class InteractionClassifierTest {

    private val classifier = InteractionClassifier()

    @Test
    fun `ticket assignment is classified correctly`() {
        val event = Event.TaskCreated(
            eventId = "evt-123",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("ProductManagerAgent"),
            taskId = "AMPERE-47",
            description = "Implement user authentication",
            assignedTo = "CodeWriterAgent",
        )

        val interaction = classifier.classify(event)

        assertNotNull(interaction, "Task assignment should be classified as an interaction")
        assertEquals(InteractionType.TICKET_ASSIGNED, interaction.interactionType)
        assertEquals("ProductManagerAgent", interaction.sourceAgentId)
        assertEquals("CodeWriterAgent", interaction.targetAgentId)
        assertEquals("Implement user authentication", interaction.context)
    }

    @Test
    fun `self-assigned task is not classified as coordination`() {
        val event = Event.TaskCreated(
            eventId = "evt-124",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("CodeWriterAgent"),
            taskId = "AMPERE-48",
            description = "Self-assigned task",
            assignedTo = "CodeWriterAgent",
        )

        val interaction = classifier.classify(event)

        assertNull(interaction, "Self-assigned tasks should not be classified as coordination")
    }

    @Test
    fun `unassigned task is not classified as coordination`() {
        val event = Event.TaskCreated(
            eventId = "evt-125",
            urgency = Urgency.LOW,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("ProductManagerAgent"),
            taskId = "AMPERE-49",
            description = "Unassigned task",
            assignedTo = null,
        )

        val interaction = classifier.classify(event)

        assertNull(interaction, "Unassigned tasks should not be classified as coordination")
    }

    @Test
    fun `question raised is classified as clarification request`() {
        val event = Event.QuestionRaised(
            eventId = "evt-200",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("CodeWriterAgent"),
            questionText = "What authentication library should we use?",
            context = "Working on authentication feature",
        )

        val interaction = classifier.classify(event)

        assertNotNull(interaction, "Question should be classified as an interaction")
        assertEquals(InteractionType.CLARIFICATION_REQUEST, interaction.interactionType)
        assertEquals("CodeWriterAgent", interaction.sourceAgentId)
        assertNull(interaction.targetAgentId, "Questions without @mentions should have null target")
    }

    @Test
    fun `question with mention is classified with target agent`() {
        val event = Event.QuestionRaised(
            eventId = "evt-201",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("CodeWriterAgent"),
            questionText = "Hey @SecurityAgent, what authentication library should we use?",
            context = "Working on authentication feature",
        )

        val interaction = classifier.classify(event)

        assertNotNull(interaction, "Question with @mention should be classified as an interaction")
        assertEquals(InteractionType.CLARIFICATION_REQUEST, interaction.interactionType)
        assertEquals("CodeWriterAgent", interaction.sourceAgentId)
        assertEquals("SecurityAgent", interaction.targetAgentId, "@mention should be extracted as target")
    }

    @Test
    fun `code review request is classified correctly`() {
        val event = Event.CodeSubmitted(
            eventId = "evt-300",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("CodeWriterAgent"),
            filePath = "src/auth/Authentication.kt",
            changeDescription = "Implemented OAuth2 authentication flow",
            reviewRequired = true,
            assignedTo = "SecurityAgent",
        )

        val interaction = classifier.classify(event)

        assertNotNull(interaction, "Code review request should be classified as an interaction")
        assertEquals(InteractionType.REVIEW_REQUEST, interaction.interactionType)
        assertEquals("CodeWriterAgent", interaction.sourceAgentId)
        assertEquals("SecurityAgent", interaction.targetAgentId)
        assertEquals("src/auth/Authentication.kt: Implemented OAuth2 authentication flow", interaction.context)
    }

    @Test
    fun `code submission without review is not classified as coordination`() {
        val event = Event.CodeSubmitted(
            eventId = "evt-301",
            urgency = Urgency.LOW,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("CodeWriterAgent"),
            filePath = "src/util/Helper.kt",
            changeDescription = "Added helper function",
            reviewRequired = false,
            assignedTo = "SecurityAgent",
        )

        val interaction = classifier.classify(event)

        assertNull(interaction, "Code submission without review should not be classified as coordination")
    }

    @Test
    fun `code review request without assignee is not classified as coordination`() {
        val event = Event.CodeSubmitted(
            eventId = "evt-302",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("CodeWriterAgent"),
            filePath = "src/util/Helper.kt",
            changeDescription = "Added helper function",
            reviewRequired = true,
            assignedTo = null,
        )

        val interaction = classifier.classify(event)

        assertNull(interaction, "Code review without assignee should not be classified as coordination")
    }

    @Test
    fun `notification to agent is classified as delegation`() {
        val taskEvent = Event.TaskCreated(
            eventId = "evt-400",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("ManagerAgent"),
            taskId = "AMPERE-50",
            description = "Review pull request",
            assignedTo = "ReviewerAgent",
        )

        val notification = NotificationEvent.ToAgent<EventSubscription>(
            agentId = "ReviewerAgent",
            event = taskEvent,
            subscription = null,
        )

        val interaction = classifier.classify(notification)

        assertNotNull(interaction, "Notification to agent should be classified as an interaction")
        assertEquals(InteractionType.DELEGATION, interaction.interactionType)
        assertEquals("ManagerAgent", interaction.sourceAgentId)
        assertEquals("ReviewerAgent", interaction.targetAgentId)
    }

    @Test
    fun `notification to self is not classified as coordination`() {
        val taskEvent = Event.TaskCreated(
            eventId = "evt-401",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("SelfAgent"),
            taskId = "AMPERE-51",
            description = "Self-notification",
            assignedTo = "SelfAgent",
        )

        val notification = NotificationEvent.ToAgent<EventSubscription>(
            agentId = "SelfAgent",
            event = taskEvent,
            subscription = null,
        )

        val interaction = classifier.classify(notification)

        assertNull(interaction, "Self-notifications should not be classified as coordination")
    }

    @Test
    fun `notification to human is classified as escalation`() {
        val questionEvent = Event.QuestionRaised(
            eventId = "evt-500",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("BlockedAgent"),
            questionText = "I need human input to proceed",
            context = "Blocked on decision",
        )

        val notification = NotificationEvent.ToHuman<EventSubscription>(
            event = questionEvent,
            subscription = null,
        )

        val interaction = classifier.classify(notification)

        assertNotNull(interaction, "Notification to human should be classified as an interaction")
        assertEquals(InteractionType.HUMAN_ESCALATION, interaction.interactionType)
        assertEquals("BlockedAgent", interaction.sourceAgentId)
        assertNull(interaction.targetAgentId, "Human escalation should have null targetAgentId")
    }

    @Test
    fun `extractMention finds first mention in text`() {
        val mention1 = classifier.extractMention("Hey @Alice, can you help?")
        assertEquals("Alice", mention1)

        val mention2 = classifier.extractMention("Ping @Bob and @Charlie for review")
        assertEquals("Bob", mention2, "Should extract first mention")

        val mention3 = classifier.extractMention("No mentions here")
        assertNull(mention3, "Should return null when no mention found")

        val mention4 = classifier.extractMention("Email alice@example.com is not a mention")
        assertNull(mention4, "Email addresses should not be matched as mentions")
    }

    @Test
    fun `truncate shortens text correctly`() {
        val short = "Short text"
        assertEquals("Short text", classifier.truncate(short, 50))

        val long = "This is a very long text that needs to be truncated to fit within the maximum length"
        val truncated = classifier.truncate(long, 30)
        assertEquals(30, truncated.length)
        assertEquals("This is a very long text th...", truncated)
    }

    @Test
    fun `truncate preserves text that fits exactly`() {
        val text = "Exactly twenty chars"
        assertEquals(text, classifier.truncate(text, 20))
    }

    @Test
    fun `truncate handles edge case at boundary`() {
        val text = "12345"
        val truncated = classifier.truncate(text, 4)
        assertEquals("1...", truncated)
    }

    @Test
    fun `context is truncated in interactions`() {
        val longDescription = "A".repeat(200)
        val event = Event.TaskCreated(
            eventId = "evt-600",
            urgency = Urgency.LOW,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("Agent1"),
            taskId = "TASK-1",
            description = longDescription,
            assignedTo = "Agent2",
        )

        val interaction = classifier.classify(event)

        assertNotNull(interaction)
        assertNotNull(interaction.context)
        assertEquals(100, interaction.context!!.length, "Context should be truncated to 100 chars")
        assertEquals("...", interaction.context!!.takeLast(3), "Truncated context should end with ...")
    }

    @Test
    fun `question notification is classified as help request`() {
        val questionEvent = Event.QuestionRaised(
            eventId = "evt-700",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("JuniorAgent"),
            questionText = "How do I handle null values?",
            context = "Working on data validation",
        )

        val notification = NotificationEvent.ToAgent<EventSubscription>(
            agentId = "SeniorAgent",
            event = questionEvent,
            subscription = null,
        )

        val interaction = classifier.classify(notification)

        assertNotNull(interaction)
        assertEquals(InteractionType.HELP_REQUEST, interaction.interactionType)
        assertEquals("JuniorAgent", interaction.sourceAgentId)
        assertEquals("SeniorAgent", interaction.targetAgentId)
    }

    @Test
    fun `code review notification is classified as review request`() {
        val codeEvent = Event.CodeSubmitted(
            eventId = "evt-800",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("Developer"),
            filePath = "src/main/Feature.kt",
            changeDescription = "New feature implementation",
            reviewRequired = true,
            assignedTo = "Reviewer",
        )

        val notification = NotificationEvent.ToAgent<EventSubscription>(
            agentId = "Reviewer",
            event = codeEvent,
            subscription = null,
        )

        val interaction = classifier.classify(notification)

        assertNotNull(interaction)
        assertEquals(InteractionType.REVIEW_REQUEST, interaction.interactionType)
    }
}
