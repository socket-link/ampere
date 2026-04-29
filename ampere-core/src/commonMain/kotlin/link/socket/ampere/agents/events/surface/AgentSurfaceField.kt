package link.socket.ampere.agents.events.surface

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A typed input field that can appear inside an [AgentSurface.Form].
 *
 * Validation predicates are expressed in pure Kotlin so they run identically on
 * every target. Renderers may surface failures inline, but the canonical check
 * is [validate]; the platform must not accept a [AgentSurfaceFieldValue] that
 * fails validation.
 */
@Serializable
sealed interface AgentSurfaceField {

    /** Stable id used as the key in [AgentSurfaceResponse.Submitted.values]. */
    val id: String

    /** Human-readable label shown next to the field. */
    val label: String

    /** Optional helper text displayed beneath the field. */
    val helpText: String?

    /** Whether a value is required for submission. */
    val required: Boolean

    /**
     * Validate a field value of the matching variant. Returns
     * [FieldValidationResult.Valid] on success, otherwise a list of human-
     * readable error messages.
     */
    fun validate(value: AgentSurfaceFieldValue?): FieldValidationResult

    /** Free-form single-line or multi-line text. */
    @Serializable
    data class Text(
        override val id: String,
        override val label: String,
        override val helpText: String? = null,
        override val required: Boolean = false,
        val placeholder: String? = null,
        val multiline: Boolean = false,
        val minLength: Int = 0,
        val maxLength: Int? = null,
        val pattern: String? = null,
    ) : AgentSurfaceField {

        override fun validate(value: AgentSurfaceFieldValue?): FieldValidationResult {
            val errors = mutableListOf<String>()
            val text = (value as? AgentSurfaceFieldValue.TextValue)?.value
            if (text.isNullOrEmpty()) {
                if (required) errors += "$label is required"
                return FieldValidationResult.from(errors)
            }
            if (text.length < minLength) errors += "$label must be at least $minLength characters"
            maxLength?.let { if (text.length > it) errors += "$label must be at most $it characters" }
            pattern?.let {
                if (!Regex(it).matches(text)) errors += "$label is not in the expected format"
            }
            return FieldValidationResult.from(errors)
        }
    }

    /** A numeric input. Renderers may show a stepper, slider, or text field. */
    @Serializable
    data class Number(
        override val id: String,
        override val label: String,
        override val helpText: String? = null,
        override val required: Boolean = false,
        val min: Double? = null,
        val max: Double? = null,
        val integerOnly: Boolean = false,
    ) : AgentSurfaceField {

        override fun validate(value: AgentSurfaceFieldValue?): FieldValidationResult {
            val errors = mutableListOf<String>()
            val number = (value as? AgentSurfaceFieldValue.NumberValue)?.value
            if (number == null) {
                if (required) errors += "$label is required"
                return FieldValidationResult.from(errors)
            }
            if (integerOnly && number != number.toLong().toDouble()) {
                errors += "$label must be a whole number"
            }
            min?.let { if (number < it) errors += "$label must be at least $it" }
            max?.let { if (number > it) errors += "$label must be at most $it" }
            return FieldValidationResult.from(errors)
        }
    }

    /** Boolean toggle (checkbox or switch). */
    @Serializable
    data class Toggle(
        override val id: String,
        override val label: String,
        override val helpText: String? = null,
        override val required: Boolean = false,
        val default: Boolean = false,
    ) : AgentSurfaceField {

        override fun validate(value: AgentSurfaceFieldValue?): FieldValidationResult {
            val toggled = (value as? AgentSurfaceFieldValue.ToggleValue)?.value
            if (required && toggled != true) {
                return FieldValidationResult.Invalid(listOf("$label must be enabled"))
            }
            return FieldValidationResult.Valid
        }
    }

    /** A date and/or time picker, expressed as an [Instant]. */
    @Serializable
    data class DateTime(
        override val id: String,
        override val label: String,
        override val helpText: String? = null,
        override val required: Boolean = false,
        val notBefore: Instant? = null,
        val notAfter: Instant? = null,
        val includeTime: Boolean = true,
    ) : AgentSurfaceField {

        override fun validate(value: AgentSurfaceFieldValue?): FieldValidationResult {
            val errors = mutableListOf<String>()
            val instant = (value as? AgentSurfaceFieldValue.DateTimeValue)?.value
            if (instant == null) {
                if (required) errors += "$label is required"
                return FieldValidationResult.from(errors)
            }
            notBefore?.let { if (instant < it) errors += "$label must not be before $it" }
            notAfter?.let { if (instant > it) errors += "$label must not be after $it" }
            return FieldValidationResult.from(errors)
        }
    }

    /** Single- or multi-pick from a fixed set of options inline within a form. */
    @Serializable
    data class Selection(
        override val id: String,
        override val label: String,
        override val helpText: String? = null,
        override val required: Boolean = false,
        val options: List<AgentSurface.Choice.Option>,
        val multiSelect: Boolean = false,
        val minSelections: Int = if (multiSelect) 0 else 1,
        val maxSelections: Int = if (multiSelect) options.size else 1,
    ) : AgentSurfaceField {

        override fun validate(value: AgentSurfaceFieldValue?): FieldValidationResult {
            val errors = mutableListOf<String>()
            val selections = (value as? AgentSurfaceFieldValue.SelectionValue)?.selectedIds.orEmpty()
            if (selections.isEmpty()) {
                if (required) errors += "$label is required"
                return FieldValidationResult.from(errors)
            }
            if (selections.size < minSelections) errors += "$label requires at least $minSelections selection(s)"
            if (selections.size > maxSelections) errors += "$label allows at most $maxSelections selection(s)"
            val validIds = options.map { it.id }.toSet()
            val unknown = selections.filterNot { it in validIds }
            if (unknown.isNotEmpty()) errors += "$label has unknown options: ${unknown.joinToString()}"
            return FieldValidationResult.from(errors)
        }
    }

    /**
     * A masked/secret input. Carries the same validation primitives as [Text]
     * but renderers must avoid logging or persisting the value beyond the
     * scope of the originating Plugin.
     */
    @Serializable
    data class Secret(
        override val id: String,
        override val label: String,
        override val helpText: String? = null,
        override val required: Boolean = false,
        val minLength: Int = 0,
        val maxLength: Int? = null,
    ) : AgentSurfaceField {

        override fun validate(value: AgentSurfaceFieldValue?): FieldValidationResult {
            val errors = mutableListOf<String>()
            val text = (value as? AgentSurfaceFieldValue.SecretValue)?.value
            if (text.isNullOrEmpty()) {
                if (required) errors += "$label is required"
                return FieldValidationResult.from(errors)
            }
            if (text.length < minLength) errors += "$label must be at least $minLength characters"
            maxLength?.let { if (text.length > it) errors += "$label must be at most $it characters" }
            return FieldValidationResult.from(errors)
        }
    }
}

/**
 * Typed value returned from the renderer for a single field. The variant must
 * line up with the corresponding [AgentSurfaceField] variant.
 */
@Serializable
sealed interface AgentSurfaceFieldValue {

    @Serializable
    data class TextValue(val value: String) : AgentSurfaceFieldValue

    @Serializable
    data class NumberValue(val value: Double) : AgentSurfaceFieldValue

    @Serializable
    data class ToggleValue(val value: Boolean) : AgentSurfaceFieldValue

    @Serializable
    data class DateTimeValue(val value: Instant) : AgentSurfaceFieldValue

    @Serializable
    data class SelectionValue(val selectedIds: List<String>) : AgentSurfaceFieldValue

    @Serializable
    data class SecretValue(val value: String) : AgentSurfaceFieldValue
}

/** Outcome of running [AgentSurfaceField.validate] on a single value. */
@Serializable
sealed interface FieldValidationResult {

    @Serializable
    data object Valid : FieldValidationResult

    @Serializable
    data class Invalid(val errors: List<String>) : FieldValidationResult

    companion object {
        fun from(errors: List<String>): FieldValidationResult =
            if (errors.isEmpty()) Valid else Invalid(errors)
    }
}
