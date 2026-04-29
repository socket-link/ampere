package link.socket.ampere.agents.events.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.AgentSurfaceEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Locks in that every code sample shipped in
 * `docs/develop/agent-surface-getting-started.md`,
 * `docs/develop/agent-surface/{form,choice,confirmation,card}.md`,
 * and `docs/develop/agent-surface-design-patterns.md`
 * type-checks against the public AgentSurface API as shipped.
 *
 * If any of these tests fail to compile or fail at runtime, the corresponding
 * documentation sample is wrong; fix the doc, not the test.
 */
class AgentSurfaceDocSamplesTest {

    @Test
    fun `getting-started Confirmation sample compiles and round-trips`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val agentId = "plugin-agent"
            val rendererAgent = "renderer-stub"
            val correlationId = generateUUID(agentId, "delete-branch", "feature/old")

            bus.subscribe<AgentSurfaceEvent.Requested, EventSubscription.ByEventClassType>(
                agentId = rendererAgent,
                eventType = AgentSurfaceEvent.Requested.EVENT_TYPE,
            ) { event, _ ->
                if (event.correlationId == correlationId) {
                    bus.publish(
                        AgentSurfaceEvent.Responded(
                            eventId = generateUUID(correlationId, rendererAgent),
                            timestamp = Clock.System.now(),
                            eventSource = EventSource.Agent(rendererAgent),
                            response = AgentSurfaceResponse.Submitted(
                                correlationId = correlationId,
                            ),
                        ),
                    )
                }
            }

            val awaiter = async {
                bus.awaitSurfaceResponse(
                    awaiterAgentId = agentId,
                    correlationId = correlationId,
                    timeout = 30.seconds,
                )
            }

            bus.emitSurfaceRequest(
                surface = AgentSurface.Confirmation(
                    correlationId = correlationId,
                    prompt = "Delete the branch 'feature/old'? This cannot be undone.",
                    severity = AgentSurface.Confirmation.Severity.Destructive,
                    confirmLabel = "Delete",
                    cancelLabel = "Keep",
                ),
                eventSource = EventSource.Agent(agentId),
            )

            val response = awaiter.await()
            assertIs<AgentSurfaceResponse.Submitted>(response)
            assertEquals(correlationId, response.correlationId)
        }
    }

    @Test
    fun `Form variant reference sample carries a typed value per field`() {
        val correlationId = "create-pr"
        val form = AgentSurface.Form(
            correlationId = correlationId,
            title = "Open a pull request",
            description = "Describe the change. Reviewers see the title and body verbatim.",
            fields = listOf(
                AgentSurfaceField.Text(
                    id = "title",
                    label = "Title",
                    required = true,
                    minLength = 3,
                    maxLength = 72,
                    placeholder = "Short, imperative summary",
                ),
                AgentSurfaceField.Text(
                    id = "body",
                    label = "Description",
                    multiline = true,
                    helpText = "Markdown is fine.",
                ),
                AgentSurfaceField.Toggle(
                    id = "draft",
                    label = "Open as draft",
                    default = false,
                ),
            ),
            submitLabel = "Open PR",
        )

        assertEquals(3, form.fields.size)
        val titleField = form.fields[0] as AgentSurfaceField.Text
        assertEquals(
            FieldValidationResult.Valid,
            titleField.validate(AgentSurfaceFieldValue.TextValue("Open ticket browser")),
        )
        val tooShort = titleField.validate(AgentSurfaceFieldValue.TextValue("hi"))
        assertIs<FieldValidationResult.Invalid>(tooShort)
        assertTrue(tooShort.errors.any { it.contains("at least 3") })
    }

    @Test
    fun `Choice single-select sample produces SelectionValue under SELECTION_KEY`() {
        val correlationId = "pick-base-branch"
        val choice = AgentSurface.Choice(
            correlationId = correlationId,
            title = "Pick a base branch",
            description = "The PR will target this branch.",
            options = listOf(
                AgentSurface.Choice.Option(id = "main", label = "main"),
                AgentSurface.Choice.Option(id = "develop", label = "develop"),
                AgentSurface.Choice.Option(
                    id = "release",
                    label = "release",
                    description = "Frozen during the release window.",
                    enabled = false,
                ),
            ),
        )

        assertEquals(false, choice.multiSelect)
        assertEquals(1, choice.minSelections)
        assertEquals(1, choice.maxSelections)

        val response = AgentSurfaceResponse.Submitted(
            correlationId = correlationId,
            values = mapOf(
                AgentSurfaceResponse.SELECTION_KEY to AgentSurfaceFieldValue.SelectionValue(
                    selectedIds = listOf("main"),
                ),
            ),
        )
        val selection = response.values[AgentSurfaceResponse.SELECTION_KEY]
            as? AgentSurfaceFieldValue.SelectionValue
        assertEquals(listOf("main"), selection?.selectedIds)
    }

    @Test
    fun `Choice multi-select sample bounds selections by min and max`() {
        val choice = AgentSurface.Choice(
            correlationId = "pick-reviewers",
            title = "Pick reviewers",
            options = listOf(
                AgentSurface.Choice.Option(id = "@alice", label = "Alice"),
                AgentSurface.Choice.Option(id = "@bob", label = "Bob"),
                AgentSurface.Choice.Option(id = "@carol", label = "Carol"),
                AgentSurface.Choice.Option(id = "@dan", label = "Dan"),
            ),
            multiSelect = true,
            minSelections = 1,
            maxSelections = 3,
        )

        assertEquals(true, choice.multiSelect)
        assertEquals(1, choice.minSelections)
        assertEquals(3, choice.maxSelections)
        assertEquals(4, choice.options.size)
    }

    @Test
    fun `Confirmation reference sample preserves severity through the bus`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val agentId = "plugin-agent"
            val rendererAgent = "renderer-stub"
            val correlationId = generateUUID(agentId, "force-push", "feature/x")

            bus.subscribe<AgentSurfaceEvent.Requested, EventSubscription.ByEventClassType>(
                agentId = rendererAgent,
                eventType = AgentSurfaceEvent.Requested.EVENT_TYPE,
            ) { event, _ ->
                val confirmation = event.surface as AgentSurface.Confirmation
                assertEquals(
                    AgentSurface.Confirmation.Severity.Destructive,
                    confirmation.severity,
                )
                bus.publish(
                    AgentSurfaceEvent.Responded(
                        eventId = generateUUID(event.correlationId, rendererAgent),
                        timestamp = Clock.System.now(),
                        eventSource = EventSource.Agent(rendererAgent),
                        response = AgentSurfaceResponse.Cancelled(
                            correlationId = event.correlationId,
                            reason = "user-dismissed",
                        ),
                    ),
                )
            }

            val awaiter = async {
                bus.awaitSurfaceResponse(
                    awaiterAgentId = agentId,
                    correlationId = correlationId,
                    timeout = 60.seconds,
                )
            }

            bus.emitSurfaceRequest(
                surface = AgentSurface.Confirmation(
                    correlationId = correlationId,
                    prompt = "Force-push 'feature/x'? Anyone with this branch checked out " +
                        "will need to reset to the new history.",
                    severity = AgentSurface.Confirmation.Severity.Destructive,
                    confirmLabel = "Force-push",
                    cancelLabel = "Stop",
                ),
                eventSource = EventSource.Agent(agentId),
            )

            val response = awaiter.await()
            val cancelled = assertIs<AgentSurfaceResponse.Cancelled>(response)
            assertEquals("user-dismissed", cancelled.reason)
        }
    }

    @Test
    fun `Card reference sample dispatches by chosenAction`() {
        val correlationId = "show-pr-result"
        val card = AgentSurface.Card(
            correlationId = correlationId,
            title = "Pull request opened",
            slots = listOf(
                AgentSurface.Card.Slot.Body(text = "PR #42 is open against main."),
                AgentSurface.Card.Slot.KeyValue(key = "Title", value = "Tighten branch rules"),
                AgentSurface.Card.Slot.KeyValue(key = "Author", value = "@alice"),
                AgentSurface.Card.Slot.KeyValue(key = "Reviewers", value = "@bob, @carol"),
                AgentSurface.Card.Slot.Heading(text = "Diff summary", level = 3),
                AgentSurface.Card.Slot.Code(
                    source = "+ 12 -3 link/socket/ampere/agents/events/surface/AgentSurface.kt",
                    language = "diff",
                ),
            ),
            actions = listOf(
                AgentSurface.Card.Action(id = "open-in-browser", label = "Open in browser"),
                AgentSurface.Card.Action(
                    id = "close-pr",
                    label = "Close PR",
                    severity = AgentSurface.Confirmation.Severity.Destructive,
                ),
            ),
        )

        assertEquals(6, card.slots.size)
        assertEquals(2, card.actions.size)
        assertEquals(
            AgentSurface.Confirmation.Severity.Destructive,
            card.actions[1].severity,
        )

        val submitted = AgentSurfaceResponse.Submitted(
            correlationId = correlationId,
            chosenAction = "open-in-browser",
        )
        assertEquals("open-in-browser", submitted.chosenAction)
        assertTrue(submitted.values.isEmpty())
    }

    @Test
    fun `every Card Slot kind constructs and is recognisable in a when`() {
        val slots: List<AgentSurface.Card.Slot> = listOf(
            AgentSurface.Card.Slot.Heading(text = "Section", level = 2),
            AgentSurface.Card.Slot.Body(text = "Plain text only."),
            AgentSurface.Card.Slot.KeyValue(key = "Status", value = "Open"),
            AgentSurface.Card.Slot.Image(url = "https://example.test/img.png", altText = "diagram"),
            AgentSurface.Card.Slot.Code(source = "val x = 1", language = "kotlin"),
        )
        val labels = slots.map {
            when (it) {
                is AgentSurface.Card.Slot.Heading -> "heading"
                is AgentSurface.Card.Slot.Body -> "body"
                is AgentSurface.Card.Slot.KeyValue -> "keyvalue"
                is AgentSurface.Card.Slot.Image -> "image"
                is AgentSurface.Card.Slot.Code -> "code"
            }
        }
        assertEquals(listOf("heading", "body", "keyvalue", "image", "code"), labels)
    }

    @Test
    fun `every AgentSurface variant is exhaustive in a when block`() {
        val surfaces: List<AgentSurface> = listOf(
            AgentSurface.Form(
                correlationId = "f",
                title = "f",
                fields = listOf(
                    AgentSurfaceField.Text(id = "x", label = "X"),
                ),
            ),
            AgentSurface.Choice(
                correlationId = "c",
                title = "c",
                options = listOf(AgentSurface.Choice.Option(id = "a", label = "A")),
            ),
            AgentSurface.Confirmation(correlationId = "k", prompt = "?"),
            AgentSurface.Card(
                correlationId = "d",
                title = "d",
                slots = listOf(AgentSurface.Card.Slot.Body(text = "hi")),
            ),
        )
        val kinds = surfaces.map {
            when (it) {
                is AgentSurface.Form -> "form"
                is AgentSurface.Choice -> "choice"
                is AgentSurface.Confirmation -> "confirmation"
                is AgentSurface.Card -> "card"
            }
        }
        assertEquals(listOf("form", "choice", "confirmation", "card"), kinds)
    }

    @Test
    fun `every AgentSurfaceResponse variant is exhaustive in a when block`() {
        val responses: List<AgentSurfaceResponse> = listOf(
            AgentSurfaceResponse.Submitted(correlationId = "s"),
            AgentSurfaceResponse.Cancelled(correlationId = "c", reason = "user"),
            AgentSurfaceResponse.TimedOut(correlationId = "t", timeoutMillis = 100L),
        )
        val kinds = responses.map {
            when (it) {
                is AgentSurfaceResponse.Submitted -> "submitted"
                is AgentSurfaceResponse.Cancelled -> "cancelled"
                is AgentSurfaceResponse.TimedOut -> "timed-out"
            }
        }
        assertEquals(listOf("submitted", "cancelled", "timed-out"), kinds)
    }

    @Test
    fun `getting-started end-to-end function returns true on Submitted`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val agentId = "plugin-agent"
            val rendererAgent = "renderer-stub"
            val branchName = "feature/old"

            bus.subscribe<AgentSurfaceEvent.Requested, EventSubscription.ByEventClassType>(
                agentId = rendererAgent,
                eventType = AgentSurfaceEvent.Requested.EVENT_TYPE,
            ) { event, _ ->
                bus.publish(
                    AgentSurfaceEvent.Responded(
                        eventId = generateUUID(event.correlationId, rendererAgent),
                        timestamp = Clock.System.now(),
                        eventSource = EventSource.Agent(rendererAgent),
                        response = AgentSurfaceResponse.Submitted(
                            correlationId = event.correlationId,
                        ),
                    ),
                )
            }

            val confirmed = confirmDeleteBranch(bus, agentId, branchName)
            assertEquals(true, confirmed)
        }
    }

    private suspend fun confirmDeleteBranch(
        bus: EventSerialBus,
        agentId: String,
        branchName: String,
    ): Boolean {
        val correlationId = generateUUID(agentId, "delete-branch", branchName)

        bus.emitSurfaceRequest(
            surface = AgentSurface.Confirmation(
                correlationId = correlationId,
                prompt = "Delete the branch '$branchName'? This cannot be undone.",
                severity = AgentSurface.Confirmation.Severity.Destructive,
                confirmLabel = "Delete",
                cancelLabel = "Keep",
            ),
            eventSource = EventSource.Agent(agentId),
        )

        return when (
            bus.awaitSurfaceResponse(
                awaiterAgentId = agentId,
                correlationId = correlationId,
                timeout = 30.seconds,
            )
        ) {
            is AgentSurfaceResponse.Submitted -> true
            is AgentSurfaceResponse.Cancelled,
            is AgentSurfaceResponse.TimedOut,
            -> false
        }
    }

    @Test
    fun `Form sample readback uses safe casts on optional fields`() {
        val response = AgentSurfaceResponse.Submitted(
            correlationId = "create-pr",
            values = mapOf(
                "title" to AgentSurfaceFieldValue.TextValue("Tighten branch rules"),
                "draft" to AgentSurfaceFieldValue.ToggleValue(true),
            ),
        )
        val title = (response.values["title"] as AgentSurfaceFieldValue.TextValue).value
        val body = (response.values["body"] as? AgentSurfaceFieldValue.TextValue)?.value.orEmpty()
        val draft = (response.values["draft"] as? AgentSurfaceFieldValue.ToggleValue)?.value ?: false

        assertEquals("Tighten branch rules", title)
        assertEquals("", body)
        assertEquals(true, draft)
    }

    @Test
    fun `unused timeout duration sample compiles`() {
        // Doc samples reference 2.minutes and 5.minutes; ensure the import is wired up.
        val twoMinutes = 2.minutes
        val fiveMinutes = 5.minutes
        assertTrue(twoMinutes.inWholeSeconds == 120L)
        assertTrue(fiveMinutes.inWholeSeconds == 300L)
    }
}
