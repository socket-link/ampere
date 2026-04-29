package link.socket.ampere.agents.events.surface

import kotlinx.serialization.Serializable

/**
 * A correlation identifier used to pair a surface request with its response.
 *
 * Plugins generate one of these per [AgentSurface] they emit and pass it to
 * [link.socket.ampere.agents.events.surface.awaitSurfaceResponse] to receive
 * the [AgentSurfaceResponse] produced by the platform renderer.
 */
typealias CorrelationId = String

/**
 * A typed, serializable description of a UI render request emitted by a Plugin.
 *
 * Platform renderers (iOS, Android Compose Multiplatform, Desktop) translate each
 * variant into native UI. This contract lives in commonMain and intentionally
 * carries no platform or framework references so it can be expressed across
 * every Ampere target.
 *
 * Every variant carries a [correlationId] so the emitting Plugin can wait for
 * the matching [AgentSurfaceResponse] without coupling to bus internals.
 */
@Serializable
sealed interface AgentSurface {

    /** Stable identifier used to pair this surface with its response. */
    val correlationId: CorrelationId

    /**
     * A multi-field form. Renderers display the [fields] in order and submit a
     * map of field id to typed value when the user confirms.
     */
    @Serializable
    data class Form(
        override val correlationId: CorrelationId,
        val title: String,
        val description: String? = null,
        val fields: List<AgentSurfaceField>,
        val submitLabel: String = "Submit",
        val cancelLabel: String = "Cancel",
    ) : AgentSurface

    /**
     * A single- or multi-select picker. Renderers display the [options] in
     * order and submit the chosen option ids.
     */
    @Serializable
    data class Choice(
        override val correlationId: CorrelationId,
        val title: String,
        val description: String? = null,
        val options: List<Option>,
        val multiSelect: Boolean = false,
        val minSelections: Int = if (multiSelect) 0 else 1,
        val maxSelections: Int = if (multiSelect) options.size else 1,
    ) : AgentSurface {

        @Serializable
        data class Option(
            val id: String,
            val label: String,
            val description: String? = null,
            val enabled: Boolean = true,
        )
    }

    /**
     * A confirmation prompt. Renderers display [prompt] with an affordance for
     * accept/reject. Use [severity] to influence destructive vs. neutral
     * styling without leaking renderer types.
     */
    @Serializable
    data class Confirmation(
        override val correlationId: CorrelationId,
        val prompt: String,
        val severity: Severity = Severity.Info,
        val confirmLabel: String = "Confirm",
        val cancelLabel: String = "Cancel",
    ) : AgentSurface {

        @Serializable
        enum class Severity {
            Info,
            Warning,
            Destructive,
        }
    }

    /**
     * A rich, slot-based card. Each [Slot] is a typed content fragment that the
     * renderer composes; new slot kinds can be added without breaking existing
     * renderers because slots are sealed.
     */
    @Serializable
    data class Card(
        override val correlationId: CorrelationId,
        val title: String,
        val slots: List<Slot>,
        val actions: List<Action> = emptyList(),
    ) : AgentSurface {

        @Serializable
        sealed interface Slot {

            @Serializable
            data class Heading(val text: String, val level: Int = 2) : Slot

            @Serializable
            data class Body(val text: String) : Slot

            @Serializable
            data class KeyValue(val key: String, val value: String) : Slot

            @Serializable
            data class Image(val url: String, val altText: String? = null) : Slot

            @Serializable
            data class Code(val source: String, val language: String? = null) : Slot
        }

        @Serializable
        data class Action(
            val id: String,
            val label: String,
            val severity: Confirmation.Severity = Confirmation.Severity.Info,
        )
    }
}
