package link.socket.ampere.cli.surface

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.AgentSurfaceEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.surface.AgentSurface
import link.socket.ampere.agents.events.surface.AgentSurfaceField
import link.socket.ampere.agents.events.surface.AgentSurfaceFieldValue
import link.socket.ampere.agents.events.surface.AgentSurfaceResponse
import link.socket.ampere.agents.events.surface.FieldValidationResult
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Generic terminal renderer for [AgentSurface] events.
 *
 * Subscribes to [AgentSurfaceEvent.Requested.EVENT_TYPE] on the supplied bus,
 * renders each surface variant to [output], reads input from [input], and
 * publishes [AgentSurfaceEvent.Responded] with the matching `correlationId`.
 *
 * No Compose / SwiftUI / UIKit dependencies — usable on every JVM target
 * AMPERE runs on, including non-TTY environments (with [ansi] = false).
 *
 * Renders one surface at a time; concurrent emits queue behind a [Mutex] so
 * the terminal never has two prompts fighting for stdin.
 *
 * The user can abort a multi-step prompt (Form, Choice, Card with actions)
 * by typing the [cancelToken] (default `:cancel`). A [Confirmation] does not
 * need a separate cancel token — anything other than the confirm verb is
 * treated as cancel.
 */
class AgentSurfaceCliRenderer(
    private val bus: EventSerialBus,
    private val agentId: AgentId = "cli-surface-renderer",
    private val input: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    private val output: PrintWriter = PrintWriter(System.out, true),
    private val ansi: Boolean = true,
    private val cancelToken: String = ":cancel",
    private val readSecret: () -> String? = ::defaultReadSecret,
    private val now: () -> Instant = { Clock.System.now() },
) {

    private val mutex = Mutex()

    /** Subscribe to surface requests. Idempotent for a given `(bus, agentId)` pair. */
    fun start() {
        bus.subscribe<AgentSurfaceEvent.Requested, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = AgentSurfaceEvent.Requested.EVENT_TYPE,
        ) { event, _ ->
            val response = mutex.withLock {
                renderSurface(event.surface)
            }
            bus.publish(
                AgentSurfaceEvent.Responded(
                    eventId = generateUUID(event.correlationId, agentId),
                    timestamp = now(),
                    eventSource = EventSource.Agent(agentId),
                    response = response,
                ),
            )
        }
    }

    private fun renderSurface(surface: AgentSurface): AgentSurfaceResponse = when (surface) {
        is AgentSurface.Confirmation -> renderConfirmation(surface)
        is AgentSurface.Choice -> renderChoice(surface)
        is AgentSurface.Form -> renderForm(surface)
        is AgentSurface.Card -> renderCard(surface)
    }

    private fun renderConfirmation(surface: AgentSurface.Confirmation): AgentSurfaceResponse {
        printHeader("CONFIRMATION", surface.severity)
        output.println(surface.prompt)
        output.println()
        output.println("  [1] ${styledLabel(surface.confirmLabel, surface.severity)}")
        output.println("  [2] ${surface.cancelLabel}")
        output.println()
        output.print("Confirm? (1=${surface.confirmLabel}, 2=${surface.cancelLabel}) > ")
        output.flush()

        val raw = readNonNullLine().trim().lowercase()
        return when (raw) {
            "1", "y", "yes", surface.confirmLabel.lowercase() ->
                AgentSurfaceResponse.Submitted(correlationId = surface.correlationId)
            else -> AgentSurfaceResponse.Cancelled(
                correlationId = surface.correlationId,
                reason = if (raw.isEmpty()) "no-input" else "user-dismissed",
            )
        }
    }

    private fun renderChoice(surface: AgentSurface.Choice): AgentSurfaceResponse {
        printHeader("CHOICE", null)
        output.println(bold(surface.title))
        surface.description?.let { output.println(it) }
        output.println()

        surface.options.forEachIndexed { index, option ->
            val number = index + 1
            val disabledTag = if (!option.enabled) dim(" (disabled)") else ""
            output.println("  [$number] ${option.label}$disabledTag")
            option.description?.let { output.println("        ${dim(it)}") }
        }
        output.println()

        val prompt = if (surface.multiSelect) {
            "Pick ${surface.minSelections}-${surface.maxSelections} (comma-separated numbers, '$cancelToken' to cancel)"
        } else {
            "Pick one (number, '$cancelToken' to cancel)"
        }

        while (true) {
            output.print("$prompt > ")
            output.flush()
            val raw = readNonNullLine().trim()
            if (raw == cancelToken) {
                return AgentSurfaceResponse.Cancelled(
                    correlationId = surface.correlationId,
                    reason = "user-dismissed",
                )
            }
            val parsed = parseSelections(raw, surface.options.size)
            if (parsed == null) {
                output.println(error("Couldn't parse selection — type '$cancelToken' to abort."))
                continue
            }
            val selectedOptions = parsed.map { surface.options[it - 1] }
            val disabled = selectedOptions.filter { !it.enabled }
            if (disabled.isNotEmpty()) {
                output.println(error("Disabled options not selectable: ${disabled.joinToString { it.label }}"))
                continue
            }
            if (parsed.size < surface.minSelections) {
                output.println(error("Pick at least ${surface.minSelections}"))
                continue
            }
            if (parsed.size > surface.maxSelections) {
                output.println(error("Pick at most ${surface.maxSelections}"))
                continue
            }
            val ids = selectedOptions.map { it.id }
            return AgentSurfaceResponse.Submitted(
                correlationId = surface.correlationId,
                values = mapOf(
                    AgentSurfaceResponse.SELECTION_KEY to AgentSurfaceFieldValue.SelectionValue(
                        selectedIds = ids,
                    ),
                ),
            )
        }
    }

    private fun renderForm(surface: AgentSurface.Form): AgentSurfaceResponse {
        printHeader("FORM", null)
        output.println(bold(surface.title))
        surface.description?.let { output.println(it) }
        output.println()
        output.println(dim("Type '$cancelToken' on any prompt to cancel the form."))
        output.println()

        val values = mutableMapOf<String, AgentSurfaceFieldValue>()
        for (field in surface.fields) {
            val outcome = promptField(field) ?: return AgentSurfaceResponse.Cancelled(
                correlationId = surface.correlationId,
                reason = "user-dismissed",
            )
            outcome.value?.let { values[field.id] = it }
        }

        output.println()
        output.print("Submit '${surface.submitLabel}'? (1=submit, 2=${surface.cancelLabel}) > ")
        output.flush()
        val confirm = readNonNullLine().trim().lowercase()
        return if (confirm == "1" || confirm == "y" || confirm == "yes" || confirm == surface.submitLabel.lowercase()) {
            AgentSurfaceResponse.Submitted(correlationId = surface.correlationId, values = values)
        } else {
            AgentSurfaceResponse.Cancelled(
                correlationId = surface.correlationId,
                reason = "user-dismissed",
            )
        }
    }

    private fun renderCard(surface: AgentSurface.Card): AgentSurfaceResponse {
        printHeader("CARD", null)
        output.println(bold(surface.title))
        output.println()

        for (slot in surface.slots) {
            when (slot) {
                is AgentSurface.Card.Slot.Heading -> {
                    val prefix = "#".repeat(slot.level.coerceAtLeast(1).coerceAtMost(6))
                    output.println("$prefix ${bold(slot.text)}")
                    output.println()
                }
                is AgentSurface.Card.Slot.Body -> {
                    output.println(slot.text)
                    output.println()
                }
                is AgentSurface.Card.Slot.KeyValue -> {
                    output.println("  ${bold(slot.key)}: ${slot.value}")
                }
                is AgentSurface.Card.Slot.Image -> {
                    val alt = slot.altText?.let { " — $it" }.orEmpty()
                    output.println("  [image: ${slot.url}$alt]")
                }
                is AgentSurface.Card.Slot.Code -> {
                    val tag = slot.language?.let { " ($it)" }.orEmpty()
                    output.println(dim("```$tag"))
                    slot.source.lines().forEach { output.println("  $it") }
                    output.println(dim("```"))
                    output.println()
                }
            }
        }

        if (surface.actions.isEmpty()) {
            output.print("Press enter to dismiss > ")
            output.flush()
            readNonNullLine()
            return AgentSurfaceResponse.Cancelled(
                correlationId = surface.correlationId,
                reason = "dismissed",
            )
        }

        output.println()
        surface.actions.forEachIndexed { index, action ->
            output.println("  [${index + 1}] ${styledLabel(action.label, action.severity)}")
        }
        output.println("  [0] Dismiss")
        output.println()

        while (true) {
            output.print("Pick action (number, '$cancelToken' to dismiss) > ")
            output.flush()
            val raw = readNonNullLine().trim()
            if (raw == cancelToken || raw == "0" || raw.isEmpty()) {
                return AgentSurfaceResponse.Cancelled(
                    correlationId = surface.correlationId,
                    reason = "dismissed",
                )
            }
            val number = raw.toIntOrNull()
            if (number == null || number < 1 || number > surface.actions.size) {
                output.println(error("Pick a number between 1 and ${surface.actions.size}, or 0 to dismiss."))
                continue
            }
            val action = surface.actions[number - 1]
            return AgentSurfaceResponse.Submitted(
                correlationId = surface.correlationId,
                chosenAction = action.id,
            )
        }
    }

    /**
     * Prompt the user for one field. Returns:
     *  - `Some(value)` — the user supplied a valid value (or skipped an
     *     optional field with empty input).
     *  - `Some(null)` — the user supplied empty input on an optional field
     *     (encoded as a "no entry" outcome).
     *  - `null` — the user typed [cancelToken]; abort the whole form.
     */
    private fun promptField(field: AgentSurfaceField): FieldOutcome? {
        val labelLine = buildString {
            append(bold(field.label))
            if (field.required) append(dim(" (required)"))
            field.helpText?.let { append("\n  ${dim(it)}") }
        }
        output.println(labelLine)
        printFieldHint(field)

        while (true) {
            output.print("> ")
            output.flush()

            val raw = if (field is AgentSurfaceField.Secret) {
                readSecret() ?: ""
            } else {
                readNonNullLine()
            }

            if (raw == cancelToken) return null

            val parsed = parseFieldValue(field, raw)
            val validation = field.validate(parsed)
            when (validation) {
                FieldValidationResult.Valid -> return FieldOutcome(parsed)
                is FieldValidationResult.Invalid -> {
                    validation.errors.forEach { output.println(error("  - $it")) }
                }
            }
        }
    }

    private fun printFieldHint(field: AgentSurfaceField) {
        val hint = when (field) {
            is AgentSurfaceField.Text -> buildString {
                if (field.multiline) append("multi-line, end with empty line")
                if (field.minLength > 0) append(if (isEmpty()) "min ${field.minLength}" else ", min ${field.minLength}")
                field.maxLength?.let { append(if (isEmpty()) "max $it" else ", max $it") }
                field.pattern?.let { append(if (isEmpty()) "matches /$it/" else ", matches /$it/") }
            }
            is AgentSurfaceField.Number -> buildString {
                if (field.integerOnly) append("integer")
                field.min?.let { append(if (isEmpty()) "min $it" else ", min $it") }
                field.max?.let { append(if (isEmpty()) "max $it" else ", max $it") }
            }
            is AgentSurfaceField.Toggle -> "yes/no (default ${if (field.default) "yes" else "no"})"
            is AgentSurfaceField.DateTime ->
                if (field.includeTime) "ISO-8601 instant (e.g., 2026-04-29T10:00:00Z)" else "ISO-8601 date (e.g., 2026-04-29)"
            is AgentSurfaceField.Selection -> {
                val opts = field.options.mapIndexed { i, o -> "${i + 1}=${o.id}" }.joinToString(", ")
                if (field.multiSelect) "comma-separated, $opts" else "one of: $opts"
            }
            is AgentSurfaceField.Secret -> "input hidden"
        }
        if (hint.isNotEmpty()) output.println(dim("  ($hint)"))
    }

    private fun parseFieldValue(field: AgentSurfaceField, raw: String): AgentSurfaceFieldValue? {
        if (raw.isEmpty()) return null
        return when (field) {
            is AgentSurfaceField.Text -> {
                val text = if (field.multiline) readMultilineContinuation(raw) else raw
                AgentSurfaceFieldValue.TextValue(text)
            }
            is AgentSurfaceField.Number -> raw.toDoubleOrNull()?.let { AgentSurfaceFieldValue.NumberValue(it) }
            is AgentSurfaceField.Toggle -> when (raw.trim().lowercase()) {
                "y", "yes", "1", "true", "on" -> AgentSurfaceFieldValue.ToggleValue(true)
                "n", "no", "0", "false", "off" -> AgentSurfaceFieldValue.ToggleValue(false)
                else -> null
            }
            is AgentSurfaceField.DateTime -> runCatching {
                AgentSurfaceFieldValue.DateTimeValue(Instant.parse(raw))
            }.getOrNull()
            is AgentSurfaceField.Selection -> {
                val numbers = parseSelections(raw, field.options.size) ?: return null
                val ids = numbers.map { field.options[it - 1].id }
                AgentSurfaceFieldValue.SelectionValue(selectedIds = ids)
            }
            is AgentSurfaceField.Secret -> AgentSurfaceFieldValue.SecretValue(raw)
        }
    }

    private fun readMultilineContinuation(firstLine: String): String {
        val builder = StringBuilder(firstLine)
        while (true) {
            output.print(dim("…> "))
            output.flush()
            val next = input.readLine() ?: return builder.toString()
            if (next.isEmpty()) return builder.toString()
            builder.append('\n').append(next)
        }
    }

    private fun parseSelections(raw: String, maxNumber: Int): List<Int>? {
        if (raw.isEmpty()) return null
        val parts = raw.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val numbers = parts.mapNotNull { it.toIntOrNull() }
        if (numbers.size != parts.size) return null
        if (numbers.any { it < 1 || it > maxNumber }) return null
        return numbers.distinct()
    }

    private fun readNonNullLine(): String = input.readLine() ?: ""

    private fun printHeader(label: String, severity: AgentSurface.Confirmation.Severity?) {
        val tag = severity?.let { " — ${it.name.uppercase()}" }.orEmpty()
        val styled = when (severity) {
            AgentSurface.Confirmation.Severity.Destructive -> red(label + tag)
            AgentSurface.Confirmation.Severity.Warning -> yellow(label + tag)
            else -> bold(label + tag)
        }
        output.println()
        output.println("═══ $styled ═══")
    }

    private fun styledLabel(label: String, severity: AgentSurface.Confirmation.Severity): String =
        when (severity) {
            AgentSurface.Confirmation.Severity.Destructive -> red(bold(label))
            AgentSurface.Confirmation.Severity.Warning -> yellow(bold(label))
            AgentSurface.Confirmation.Severity.Info -> bold(label)
        }

    private fun bold(text: String): String = if (ansi) "[1m$text[0m" else text
    private fun dim(text: String): String = if (ansi) "[2m$text[0m" else text
    private fun red(text: String): String = if (ansi) "[31m$text[0m" else text
    private fun yellow(text: String): String = if (ansi) "[33m$text[0m" else text
    private fun error(text: String): String = if (ansi) "[31m$text[0m" else "! $text"

    /** Marker for a successfully read field value (or `null` for skipped optional). */
    private data class FieldOutcome(val value: AgentSurfaceFieldValue?)
}

/**
 * Default secret reader. Uses `System.console().readPassword()` if a console
 * is attached so the value isn't echoed; falls back to plain `readLine` if
 * no console (e.g., redirected stdin in CI), printing a one-time warning.
 */
private fun defaultReadSecret(): String? {
    val console = System.console()
    return if (console != null) {
        console.readPassword()?.concatToString()
    } else {
        readlnOrNull()
    }
}
