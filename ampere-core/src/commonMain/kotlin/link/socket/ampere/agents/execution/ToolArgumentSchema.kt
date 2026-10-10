package link.socket.ampere.agents.execution

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reader and validator for the JSON Schema subset a tool declares in
 * [FunctionTool.argumentSchema][link.socket.ampere.agents.execution.tools.FunctionTool.argumentSchema].
 *
 * The subset is deliberately small — `properties` with `type`, `description`, `default` and
 * `enum`, plus a top-level `required` array (AMPR-411). It is read straight off the
 * [JsonObject] rather than decoded into a schema class, because the schema is the consumer's
 * data and AMPERE adds no serialization format of its own to carry it. Keys the subset does
 * not name are ignored, not rejected: a consumer that already has a fuller JSON Schema for a
 * tool can hand it over unchanged and AMPERE will use the part it understands.
 *
 * Both readers of a schema share this object, so the prompt and the check cannot drift:
 * [SchemaParameterStrategy] renders [describe] into its prompt and runs [validate] over the
 * model's answer, and [ToolExecutionEngine] runs [validate] over the arguments a plan step
 * already carries to decide whether a parameter call is needed at all.
 */
object ToolArgumentSchema {

    private const val PROPERTIES = "properties"
    private const val REQUIRED = "required"
    private const val TYPE = "type"
    private const val DESCRIPTION = "description"
    private const val DEFAULT = "default"
    private const val ENUM = "enum"

    private const val TYPE_STRING = "string"
    private const val TYPE_INTEGER = "integer"
    private const val TYPE_NUMBER = "number"
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_ARRAY = "array"
    private const val TYPE_OBJECT = "object"

    /**
     * True when [schema] names no arguments at all — an absent, empty or malformed
     * `properties` object.
     *
     * A tool in that state has nothing for a model to fill in, so
     * [ToolExecutionEngine] dispatches it with empty arguments rather than spending a
     * parameter call that could only invent fields the schema does not name.
     */
    fun declaresNoArguments(schema: JsonObject): Boolean = propertiesOf(schema).isEmpty()

    /**
     * Renders [schema] as the argument list for a parameter-generation prompt: one line per
     * argument carrying its name, declared type, whether it is required, its default and its
     * permitted values.
     *
     * Deliberately prose-shaped rather than a copy of the raw schema JSON: the model is being
     * asked to produce an *instance*, and a schema pasted next to an instruction to emit an
     * instance is the most reliable way to get a schema back.
     */
    fun describe(schema: JsonObject): String {
        val properties = propertiesOf(schema)
        if (properties.isEmpty()) return "This tool takes no arguments."

        return properties.joinToString("\n") { property ->
            buildString {
                append("- `${property.name}`")
                append(" (${property.facets().joinToString(", ")})")
                property.description?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                if (property.enumValues.isNotEmpty()) {
                    val allowed = property.enumValues.joinToString(", ") { it.render() }
                    append(" Allowed values: $allowed.")
                }
            }
        }
    }

    /**
     * Checks [arguments] against [schema] and returns the arguments a tool should actually be
     * dispatched with.
     *
     * On [SchemaValidation.Valid] the returned object is *not* the input: it holds only the
     * properties the schema names, with each value normalised to its declared type and each
     * omitted optional filled from its `default`. That is what keeps the strategy's contract —
     * never invent a field the schema does not name — true of the model's answer as well as of
     * the prompt: a key the schema does not declare is dropped rather than passed along to the
     * tool.
     *
     * Three things are violations, and every one found is reported rather than only the first:
     * a `required` property that is missing or null, a value that cannot be read as its
     * declared type, and a value outside a declared `enum`.
     *
     * Type checking is lenient by design, because the failure it would otherwise cause is the
     * common one: a JSON string whose content reads as the declared scalar (`"20"` for an
     * `integer`, `"true"` for a `boolean`) is accepted and normalised, and a `number` with no
     * fractional part satisfies an `integer`. A property with no declared `type` is passed
     * through untouched.
     *
     * A `default` is inserted verbatim and never validated — it is the schema author's own
     * value, and failing a call over it would report a model error for an authoring mistake.
     */
    fun validate(schema: JsonObject, arguments: JsonObject): SchemaValidation {
        val violations = mutableListOf<SchemaViolation>()
        val accepted = mutableMapOf<String, JsonElement>()

        propertiesOf(schema).forEach { property ->
            val supplied = arguments[property.name]?.takeUnless { it is JsonNull }
            if (supplied == null) {
                when {
                    property.required -> violations += SchemaViolation.MissingRequired(property.name)
                    property.default != null -> accepted[property.name] = property.default
                }
                return@forEach
            }

            val declaredType = property.type
            val coerced = if (declaredType == null) {
                supplied
            } else {
                coerce(supplied, declaredType) ?: run {
                    violations += SchemaViolation.WrongType(property.name, declaredType, supplied)
                    return@forEach
                }
            }

            if (property.enumValues.isNotEmpty() && property.enumValues.none { it.sameValueAs(coerced) }) {
                violations += SchemaViolation.NotInEnum(property.name, coerced, property.enumValues)
                return@forEach
            }

            accepted[property.name] = coerced
        }

        return if (violations.isEmpty()) {
            SchemaValidation.Valid(JsonObject(accepted))
        } else {
            SchemaValidation.Invalid(violations)
        }
    }

    /**
     * The arguments [schema] declares, in declaration order.
     *
     * A property whose declaration is not an object contributes a name with no constraints,
     * which is the most useful reading of `{"properties": {"path": true}}`: the tool wants a
     * `path`, and nothing is known about its shape.
     */
    internal fun propertiesOf(schema: JsonObject): List<SchemaProperty> {
        val properties = schema[PROPERTIES] as? JsonObject ?: return emptyList()
        val required = (schema[REQUIRED] as? JsonArray)
            ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content }
            ?.toSet()
            .orEmpty()

        return properties.map { (name, declaration) ->
            val declared = declaration as? JsonObject
            SchemaProperty(
                name = name,
                type = declared?.stringOrNull(TYPE),
                description = declared?.stringOrNull(DESCRIPTION),
                default = declared?.get(DEFAULT)?.takeUnless { it is JsonNull },
                enumValues = (declared?.get(ENUM) as? JsonArray)?.toList().orEmpty(),
                required = name in required,
            )
        }
    }

    /**
     * [value] read as [type], or null when it cannot be. A type the subset does not name is
     * not enforced, so an unrecognised `type` passes its value through.
     */
    private fun coerce(value: JsonElement, type: String): JsonElement? = when (type) {
        TYPE_ARRAY -> value as? JsonArray
        TYPE_OBJECT -> value as? JsonObject
        TYPE_STRING, TYPE_INTEGER, TYPE_NUMBER, TYPE_BOOLEAN ->
            (value as? JsonPrimitive)?.let { coerceScalar(it, type) }
        else -> value
    }

    /** [primitive] read as the scalar [type], or null when its content is not one. */
    private fun coerceScalar(primitive: JsonPrimitive, type: String): JsonElement? = when (type) {
        TYPE_STRING -> JsonPrimitive(primitive.content)
        TYPE_INTEGER -> primitive.content.toIntegerOrNull()?.let { JsonPrimitive(it) }
        // A JSON number that already is one is kept verbatim, so only a quoted value is
        // rewritten: re-encoding 5 as 5.0 would change the model's answer for nothing.
        TYPE_NUMBER -> primitive.content.toDoubleOrNull()
            ?.let { if (primitive.isString) JsonPrimitive(it) else primitive }
        TYPE_BOOLEAN -> primitive.content.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
        else -> null
    }

    /** This string as a whole number, accepting the `20.0` a model writes for `20`. */
    private fun String.toIntegerOrNull(): Long? =
        toLongOrNull() ?: toDoubleOrNull()?.takeIf { it == it.toLong().toDouble() }?.toLong()

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

/**
 * One argument declared by a tool's
 * [argumentSchema][link.socket.ampere.agents.execution.tools.FunctionTool.argumentSchema].
 *
 * A flattened view of the schema subset, so the prompt renderer and the validator read the
 * same fields rather than each walking the raw [JsonObject] their own way.
 */
internal data class SchemaProperty(
    val name: String,
    val type: String?,
    val description: String?,
    val default: JsonElement?,
    val enumValues: List<JsonElement>,
    val required: Boolean,
) {

    /** The parenthesised facets of this property's rendered line. */
    fun facets(): List<String> = buildList {
        type?.let { add(it) }
        add(if (required) "required" else "optional")
        default?.let { add("default: ${it.render()}") }
    }
}

/** The verdict of [ToolArgumentSchema.validate]. */
sealed interface SchemaValidation {

    /**
     * The arguments passed the schema.
     *
     * @property arguments the normalised, schema-named-only arguments to dispatch with —
     *   not the object that was handed in. See [ToolArgumentSchema.validate].
     */
    data class Valid(val arguments: JsonObject) : SchemaValidation

    /**
     * The arguments did not pass the schema.
     *
     * @property violations every violation found, never empty.
     */
    data class Invalid(val violations: List<SchemaViolation>) : SchemaValidation {

        init {
            require(violations.isNotEmpty()) {
                "An Invalid validation must name at least one violation"
            }
        }

        /** The violations as one human-readable line, for an outcome's failure message. */
        val message: String
            get() = violations.joinToString("; ") { it.message }
    }
}

/** One way a set of tool arguments can fail its schema. */
sealed interface SchemaViolation {

    /** The argument name this violation is about. */
    val field: String

    /** What went wrong, phrased for a failure message a human reads. */
    val message: String

    /** [field] is in the schema's `required` list and was absent or null. */
    data class MissingRequired(override val field: String) : SchemaViolation {

        override val message: String = "required argument '$field' is missing"
    }

    /** [field] carries a value that cannot be read as the type the schema declares. */
    data class WrongType(
        override val field: String,
        val expected: String,
        val actual: JsonElement,
    ) : SchemaViolation {

        override val message: String =
            "argument '$field' must be of type $expected, got ${actual.kind()}"
    }

    /** [field] carries a value the schema's `enum` does not list. */
    data class NotInEnum(
        override val field: String,
        val value: JsonElement,
        val allowed: List<JsonElement>,
    ) : SchemaViolation {

        override val message: String =
            "argument '$field' must be one of ${allowed.joinToString(", ") { it.render() }}, " +
                "got ${value.render()}"
    }
}

/** This element as it would be written in JSON, for a prompt line or a failure message. */
private fun JsonElement.render(): String = when (this) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (isString) "\"$content\"" else content
    else -> toString()
}

/** The JSON kind of this element, for a type-mismatch message that does not quote the value. */
private fun JsonElement.kind(): String = when (this) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (isString) "string" else "number or boolean"
    is JsonArray -> "array"
    is JsonObject -> "object"
}

/**
 * Whether this element and [other] carry the same value.
 *
 * Primitives compare on content so an `enum` written as `["1", "2"]` still matches the `1` a
 * schema typed `integer` normalised to; anything else falls back to structural equality.
 */
private fun JsonElement.sameValueAs(other: JsonElement): Boolean = when {
    this is JsonPrimitive && other is JsonPrimitive -> content == other.content
    else -> this == other
}
