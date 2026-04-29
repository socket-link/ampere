package link.socket.ampere.agents.events.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class AgentSurfaceSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    @Test
    fun `Form surface round-trips through JSON`() {
        val original: AgentSurface = AgentSurface.Form(
            correlationId = "corr-form-1",
            title = "Create branch",
            description = "Provide a branch name and base.",
            fields = listOf(
                AgentSurfaceField.Text(
                    id = "branchName",
                    label = "Branch name",
                    required = true,
                    minLength = 1,
                    maxLength = 64,
                    pattern = "^[a-z0-9/_-]+$",
                ),
                AgentSurfaceField.Toggle(
                    id = "draft",
                    label = "Open as draft",
                    default = true,
                ),
            ),
        )

        val encoded = json.encodeToString(AgentSurface.serializer(), original)
        val decoded = json.decodeFromString(AgentSurface.serializer(), encoded)

        assertEquals(original, decoded)
        assertIs<AgentSurface.Form>(decoded)
    }

    @Test
    fun `Choice surface round-trips through JSON`() {
        val original: AgentSurface = AgentSurface.Choice(
            correlationId = "corr-choice-1",
            title = "Pick a base branch",
            options = listOf(
                AgentSurface.Choice.Option(id = "main", label = "main"),
                AgentSurface.Choice.Option(id = "develop", label = "develop", description = "integration"),
            ),
        )

        val encoded = json.encodeToString(AgentSurface.serializer(), original)
        val decoded = json.decodeFromString(AgentSurface.serializer(), encoded)

        assertEquals(original, decoded)
        assertIs<AgentSurface.Choice>(decoded)
    }

    @Test
    fun `Confirmation surface round-trips through JSON`() {
        val original: AgentSurface = AgentSurface.Confirmation(
            correlationId = "corr-conf-1",
            prompt = "Force-push to main?",
            severity = AgentSurface.Confirmation.Severity.Destructive,
            confirmLabel = "Force push",
        )

        val encoded = json.encodeToString(AgentSurface.serializer(), original)
        val decoded = json.decodeFromString(AgentSurface.serializer(), encoded)

        assertEquals(original, decoded)
        assertIs<AgentSurface.Confirmation>(decoded)
    }

    @Test
    fun `Card surface round-trips through JSON with all slot kinds`() {
        val original: AgentSurface = AgentSurface.Card(
            correlationId = "corr-card-1",
            title = "Pull request",
            slots = listOf(
                AgentSurface.Card.Slot.Heading("Summary", level = 1),
                AgentSurface.Card.Slot.Body("Adds AgentSurface primitive."),
                AgentSurface.Card.Slot.KeyValue("Files", "12"),
                AgentSurface.Card.Slot.Image("https://example.com/diff.png", altText = "Diff"),
                AgentSurface.Card.Slot.Code("println(\"hi\")", language = "kotlin"),
            ),
            actions = listOf(
                AgentSurface.Card.Action(id = "approve", label = "Approve"),
                AgentSurface.Card.Action(
                    id = "reject",
                    label = "Reject",
                    severity = AgentSurface.Confirmation.Severity.Destructive,
                ),
            ),
        )

        val encoded = json.encodeToString(AgentSurface.serializer(), original)
        val decoded = json.decodeFromString(AgentSurface.serializer(), encoded)

        assertEquals(original, decoded)
        assertIs<AgentSurface.Card>(decoded)
    }

    @Test
    fun `every AgentSurfaceField variant round-trips`() {
        val fields: List<AgentSurfaceField> = listOf(
            AgentSurfaceField.Text(id = "t", label = "T"),
            AgentSurfaceField.Number(id = "n", label = "N", min = 0.0, max = 100.0, integerOnly = true),
            AgentSurfaceField.Toggle(id = "g", label = "G"),
            AgentSurfaceField.DateTime(
                id = "d",
                label = "D",
                notBefore = Instant.fromEpochMilliseconds(0),
                notAfter = Instant.fromEpochMilliseconds(1_000_000),
            ),
            AgentSurfaceField.Selection(
                id = "s",
                label = "S",
                options = listOf(AgentSurface.Choice.Option(id = "a", label = "A")),
            ),
            AgentSurfaceField.Secret(id = "k", label = "K", minLength = 8),
        )

        for (field in fields) {
            val encoded = json.encodeToString(AgentSurfaceField.serializer(), field)
            val decoded = json.decodeFromString(AgentSurfaceField.serializer(), encoded)
            assertEquals(field, decoded, "Field $field did not round-trip cleanly")
        }
    }

    @Test
    fun `every AgentSurfaceFieldValue variant round-trips`() {
        val values: List<AgentSurfaceFieldValue> = listOf(
            AgentSurfaceFieldValue.TextValue("hello"),
            AgentSurfaceFieldValue.NumberValue(42.5),
            AgentSurfaceFieldValue.ToggleValue(true),
            AgentSurfaceFieldValue.DateTimeValue(Instant.fromEpochMilliseconds(1_700_000_000_000L)),
            AgentSurfaceFieldValue.SelectionValue(listOf("a", "b")),
            AgentSurfaceFieldValue.SecretValue("hunter2"),
        )

        for (value in values) {
            val encoded = json.encodeToString(AgentSurfaceFieldValue.serializer(), value)
            val decoded = json.decodeFromString(AgentSurfaceFieldValue.serializer(), encoded)
            assertEquals(value, decoded, "Value $value did not round-trip cleanly")
        }
    }

    @Test
    fun `every AgentSurfaceResponse variant round-trips`() {
        val responses: List<AgentSurfaceResponse> = listOf(
            AgentSurfaceResponse.Submitted(
                correlationId = "corr-1",
                values = mapOf(
                    "name" to AgentSurfaceFieldValue.TextValue("Ampere"),
                    "draft" to AgentSurfaceFieldValue.ToggleValue(false),
                ),
                chosenAction = "submit",
            ),
            AgentSurfaceResponse.Cancelled(correlationId = "corr-2", reason = "user dismissed"),
            AgentSurfaceResponse.TimedOut(correlationId = "corr-3", timeoutMillis = 30_000),
        )

        for (response in responses) {
            val encoded = json.encodeToString(AgentSurfaceResponse.serializer(), response)
            val decoded = json.decodeFromString(AgentSurfaceResponse.serializer(), encoded)
            assertEquals(response, decoded)
        }
    }

    @Test
    fun `every AgentSurface variant carries a correlationId`() {
        val surfaces: List<AgentSurface> = listOf(
            AgentSurface.Form(correlationId = "f", title = "t", fields = emptyList()),
            AgentSurface.Choice(correlationId = "c", title = "t", options = emptyList()),
            AgentSurface.Confirmation(correlationId = "cf", prompt = "?"),
            AgentSurface.Card(correlationId = "cd", title = "t", slots = emptyList()),
        )
        // Exhaustive when ensures every variant is covered at compile time.
        for (surface in surfaces) {
            val id: CorrelationId = when (surface) {
                is AgentSurface.Form -> surface.correlationId
                is AgentSurface.Choice -> surface.correlationId
                is AgentSurface.Confirmation -> surface.correlationId
                is AgentSurface.Card -> surface.correlationId
            }
            assertTrue(id.isNotEmpty())
        }
    }
}
