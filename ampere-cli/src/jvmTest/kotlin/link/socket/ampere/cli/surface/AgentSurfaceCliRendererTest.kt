package link.socket.ampere.cli.surface

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.surface.AgentSurface
import link.socket.ampere.agents.events.surface.AgentSurfaceField
import link.socket.ampere.agents.events.surface.AgentSurfaceFieldValue
import link.socket.ampere.agents.events.surface.AgentSurfaceResponse
import link.socket.ampere.agents.events.surface.awaitSurfaceResponse
import link.socket.ampere.agents.events.surface.emitSurfaceRequest

/**
 * End-to-end tests for [AgentSurfaceCliRenderer]: a real [EventSerialBus] with
 * the renderer subscribed, emitting through `emitSurfaceRequest`, awaiting via
 * `awaitSurfaceResponse`, and reading rendered output verbatim.
 */
class AgentSurfaceCliRendererTest {

    @Test
    fun `Confirmation accepts on input '1'`() = runTest {
        coroutineScope {
            val harness = harness(
                stdin = "1\n",
                bus = EventSerialBus(scope = this),
            )

            val response = harness.driveTo(
                surface = AgentSurface.Confirmation(
                    correlationId = "confirm-1",
                    prompt = "Delete branch 'feature/old'?",
                    severity = AgentSurface.Confirmation.Severity.Destructive,
                    confirmLabel = "Delete",
                    cancelLabel = "Keep",
                ),
            )

            assertIs<AgentSurfaceResponse.Submitted>(response)
            assertEquals("confirm-1", response.correlationId)
            assertTrue(harness.stdout().contains("CONFIRMATION"))
            assertTrue(harness.stdout().contains("Delete branch 'feature/old'?"))
            assertTrue(harness.stdout().contains("[1] Delete"))
            assertTrue(harness.stdout().contains("[2] Keep"))
        }
    }

    @Test
    fun `Confirmation accepts on input 'y'`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "y\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Confirmation(
                    correlationId = "confirm-y",
                    prompt = "Continue?",
                ),
            )
            assertIs<AgentSurfaceResponse.Submitted>(response)
        }
    }

    @Test
    fun `Confirmation cancels on input '2'`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "2\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Confirmation(
                    correlationId = "confirm-cancel",
                    prompt = "Continue?",
                ),
            )
            val cancelled = assertIs<AgentSurfaceResponse.Cancelled>(response)
            assertEquals("user-dismissed", cancelled.reason)
        }
    }

    @Test
    fun `Confirmation cancels on empty input`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Confirmation(
                    correlationId = "confirm-empty",
                    prompt = "Continue?",
                ),
            )
            val cancelled = assertIs<AgentSurfaceResponse.Cancelled>(response)
            assertEquals("no-input", cancelled.reason)
        }
    }

    @Test
    fun `Choice single-select picks by number`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "2\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Choice(
                    correlationId = "choice-single",
                    title = "Pick a base branch",
                    options = listOf(
                        AgentSurface.Choice.Option(id = "main", label = "main"),
                        AgentSurface.Choice.Option(id = "develop", label = "develop"),
                    ),
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            val selection = submitted.values[AgentSurfaceResponse.SELECTION_KEY]
                as AgentSurfaceFieldValue.SelectionValue
            assertEquals(listOf("develop"), selection.selectedIds)
        }
    }

    @Test
    fun `Choice multi-select picks comma-separated numbers`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "1, 3\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Choice(
                    correlationId = "choice-multi",
                    title = "Pick reviewers",
                    options = listOf(
                        AgentSurface.Choice.Option(id = "@alice", label = "Alice"),
                        AgentSurface.Choice.Option(id = "@bob", label = "Bob"),
                        AgentSurface.Choice.Option(id = "@carol", label = "Carol"),
                    ),
                    multiSelect = true,
                    minSelections = 1,
                    maxSelections = 3,
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            val selection = submitted.values[AgentSurfaceResponse.SELECTION_KEY]
                as AgentSurfaceFieldValue.SelectionValue
            assertEquals(listOf("@alice", "@carol"), selection.selectedIds)
        }
    }

    @Test
    fun `Choice rejects disabled option and re-prompts`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "3\n1\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Choice(
                    correlationId = "choice-disabled",
                    title = "Pick a base branch",
                    options = listOf(
                        AgentSurface.Choice.Option(id = "main", label = "main"),
                        AgentSurface.Choice.Option(id = "develop", label = "develop"),
                        AgentSurface.Choice.Option(id = "release", label = "release", enabled = false),
                    ),
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            val selection = submitted.values[AgentSurfaceResponse.SELECTION_KEY]
                as AgentSurfaceFieldValue.SelectionValue
            assertEquals(listOf("main"), selection.selectedIds)
            assertTrue(harness.stdout().contains("Disabled options not selectable"))
        }
    }

    @Test
    fun `Choice cancels on cancel token`() = runTest {
        coroutineScope {
            val harness = harness(stdin = ":cancel\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Choice(
                    correlationId = "choice-cancel",
                    title = "Pick one",
                    options = listOf(AgentSurface.Choice.Option(id = "a", label = "A")),
                ),
            )
            assertIs<AgentSurfaceResponse.Cancelled>(response)
        }
    }

    @Test
    fun `Form collects typed values from each field`() = runTest {
        coroutineScope {
            val harness = harness(
                stdin = "feature/test\n\nyes\n1\n",
                bus = EventSerialBus(scope = this),
            )
            val response = harness.driveTo(
                AgentSurface.Form(
                    correlationId = "form-1",
                    title = "Open a pull request",
                    fields = listOf(
                        AgentSurfaceField.Text(
                            id = "branch",
                            label = "Branch name",
                            required = true,
                            minLength = 1,
                        ),
                        AgentSurfaceField.Text(
                            id = "description",
                            label = "Description",
                        ),
                        AgentSurfaceField.Toggle(
                            id = "draft",
                            label = "Open as draft",
                        ),
                    ),
                    submitLabel = "Open PR",
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            assertEquals(
                "feature/test",
                (submitted.values["branch"] as AgentSurfaceFieldValue.TextValue).value,
            )
            // optional empty Text field is skipped (no entry in the map)
            assertEquals(false, submitted.values.containsKey("description"))
            assertEquals(
                true,
                (submitted.values["draft"] as AgentSurfaceFieldValue.ToggleValue).value,
            )
        }
    }

    @Test
    fun `Form re-prompts a field that fails validation`() = runTest {
        coroutineScope {
            val harness = harness(
                stdin = "ab\nfeature\n1\n",
                bus = EventSerialBus(scope = this),
            )
            val response = harness.driveTo(
                AgentSurface.Form(
                    correlationId = "form-validate",
                    title = "Branch name",
                    fields = listOf(
                        AgentSurfaceField.Text(
                            id = "branch",
                            label = "Branch",
                            required = true,
                            minLength = 3,
                        ),
                    ),
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            assertEquals(
                "feature",
                (submitted.values["branch"] as AgentSurfaceFieldValue.TextValue).value,
            )
            assertTrue(harness.stdout().contains("at least 3"))
        }
    }

    @Test
    fun `Form cancels with cancel token mid-field`() = runTest {
        coroutineScope {
            val harness = harness(stdin = ":cancel\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Form(
                    correlationId = "form-cancel",
                    title = "Form",
                    fields = listOf(
                        AgentSurfaceField.Text(
                            id = "x",
                            label = "X",
                            required = true,
                        ),
                    ),
                ),
            )
            val cancelled = assertIs<AgentSurfaceResponse.Cancelled>(response)
            assertEquals("user-dismissed", cancelled.reason)
        }
    }

    @Test
    fun `Form Secret field reads via injected readSecret`() = runTest {
        coroutineScope {
            val harness = harness(
                stdin = "1\n",
                bus = EventSerialBus(scope = this),
                readSecret = { "supersecret" },
            )
            val response = harness.driveTo(
                AgentSurface.Form(
                    correlationId = "form-secret",
                    title = "API token",
                    fields = listOf(
                        AgentSurfaceField.Secret(
                            id = "token",
                            label = "Token",
                            required = true,
                            minLength = 8,
                        ),
                    ),
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            val secret = submitted.values["token"] as AgentSurfaceFieldValue.SecretValue
            assertEquals("supersecret", secret.value)
            // The secret value must NOT appear in the rendered transcript.
            assertTrue("Secret leaked into stdout") { !harness.stdout().contains("supersecret") }
        }
    }

    @Test
    fun `Form Number field rejects non-integer when integerOnly is set`() = runTest {
        coroutineScope {
            val harness = harness(
                stdin = "2.5\n3\n1\n",
                bus = EventSerialBus(scope = this),
            )
            val response = harness.driveTo(
                AgentSurface.Form(
                    correlationId = "form-int",
                    title = "Count",
                    fields = listOf(
                        AgentSurfaceField.Number(
                            id = "count",
                            label = "Count",
                            required = true,
                            integerOnly = true,
                            min = 1.0,
                            max = 10.0,
                        ),
                    ),
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            val number = submitted.values["count"] as AgentSurfaceFieldValue.NumberValue
            assertEquals(3.0, number.value)
            assertTrue(harness.stdout().contains("must be a whole number"))
        }
    }

    @Test
    fun `Card with actions resolves chosenAction`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "2\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Card(
                    correlationId = "card-1",
                    title = "Pull request opened",
                    slots = listOf(
                        AgentSurface.Card.Slot.Body(text = "PR #42 is open."),
                        AgentSurface.Card.Slot.KeyValue(key = "Author", value = "@alice"),
                        AgentSurface.Card.Slot.Heading(text = "Diff", level = 3),
                        AgentSurface.Card.Slot.Code(source = "+ 1\n- 2", language = "diff"),
                    ),
                    actions = listOf(
                        AgentSurface.Card.Action(id = "open-in-browser", label = "Open in browser"),
                        AgentSurface.Card.Action(
                            id = "close-pr",
                            label = "Close PR",
                            severity = AgentSurface.Confirmation.Severity.Destructive,
                        ),
                    ),
                ),
            )
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            assertEquals("close-pr", submitted.chosenAction)
            assertTrue(harness.stdout().contains("Pull request opened"))
            assertTrue(harness.stdout().contains("Author: @alice"))
            assertTrue(harness.stdout().contains("[1] Open in browser"))
        }
    }

    @Test
    fun `Card without actions cancels on enter`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Card(
                    correlationId = "card-readonly",
                    title = "Build complete",
                    slots = listOf(AgentSurface.Card.Slot.Body(text = "All green.")),
                ),
            )
            val cancelled = assertIs<AgentSurfaceResponse.Cancelled>(response)
            assertEquals("dismissed", cancelled.reason)
        }
    }

    @Test
    fun `Card with actions dismisses on '0'`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "0\n", bus = EventSerialBus(scope = this))
            val response = harness.driveTo(
                AgentSurface.Card(
                    correlationId = "card-zero",
                    title = "Result",
                    slots = listOf(AgentSurface.Card.Slot.Body(text = "Done.")),
                    actions = listOf(
                        AgentSurface.Card.Action(id = "ok", label = "OK"),
                    ),
                ),
            )
            val cancelled = assertIs<AgentSurfaceResponse.Cancelled>(response)
            assertEquals("dismissed", cancelled.reason)
        }
    }

    @Test
    fun `concurrent surface emits are serialised by the renderer`() = runTest {
        coroutineScope {
            val harness = harness(stdin = "1\n2\n", bus = EventSerialBus(scope = this))

            val first = async {
                harness.bus.awaitSurfaceResponse(
                    awaiterAgentId = "plugin-a",
                    correlationId = "first",
                    timeout = 5.seconds,
                )
            }
            val second = async {
                harness.bus.awaitSurfaceResponse(
                    awaiterAgentId = "plugin-b",
                    correlationId = "second",
                    timeout = 5.seconds,
                )
            }

            harness.bus.emitSurfaceRequest(
                surface = AgentSurface.Confirmation(correlationId = "first", prompt = "?"),
                eventSource = EventSource.Agent("plugin-a"),
            )
            harness.bus.emitSurfaceRequest(
                surface = AgentSurface.Confirmation(correlationId = "second", prompt = "?"),
                eventSource = EventSource.Agent("plugin-b"),
            )

            val firstResponse = first.await()
            val secondResponse = second.await()
            assertIs<AgentSurfaceResponse.Submitted>(firstResponse)
            assertIs<AgentSurfaceResponse.Cancelled>(secondResponse)
        }
    }

    private fun harness(
        bus: EventSerialBus,
        stdin: String,
        readSecret: () -> String? = { null },
    ): RendererHarness {
        val outBuffer = StringWriter()
        val renderer = AgentSurfaceCliRenderer(
            bus = bus,
            agentId = "renderer-test",
            input = BufferedReader(StringReader(stdin)),
            output = PrintWriter(outBuffer, true),
            ansi = false,
            readSecret = readSecret,
            now = { Instant.fromEpochMilliseconds(0L) },
        )
        renderer.start()
        return RendererHarness(bus, outBuffer)
    }

    private class RendererHarness(
        val bus: EventSerialBus,
        private val outBuffer: StringWriter,
    ) {
        suspend fun driveTo(surface: AgentSurface): AgentSurfaceResponse = coroutineScope {
            val plugin = "plugin-driver"
            val deferred = async {
                bus.awaitSurfaceResponse(
                    awaiterAgentId = plugin,
                    correlationId = surface.correlationId,
                    timeout = 5.seconds,
                )
            }
            bus.emitSurfaceRequest(
                surface = surface,
                eventSource = EventSource.Agent(plugin),
            )
            deferred.await()
        }

        fun stdout(): String = outBuffer.toString()
    }
}
