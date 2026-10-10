package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * AMPR-411: the validator behind `SchemaParameterStrategy` and the engine's skip-when-inline
 * path, over the three-field schema the ticket names — one required argument, one with an
 * `enum`, one with a `default`.
 *
 * What is being pinned is which of a model's plausible answers are *accepted*. A validator
 * that rejects `"20"` for an `integer` turns the most common shape of LLM output into a failed
 * step, and one that accepts a value outside an `enum` hands a tool a parameter it has no
 * branch for.
 */
class ToolArgumentSchemaTest {

    @Test
    fun `a complete argument set keeps its values`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("query", "quarterly report")
                put("scope", "inbox")
                put("limit", 5)
            },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertEquals("quarterly report", valid.arguments.getValue("query").asContent())
        assertEquals("inbox", valid.arguments.getValue("scope").asContent())
        assertEquals("5", valid.arguments.getValue("limit").asContent())
    }

    @Test
    fun `an omitted argument takes the schema default`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject { put("query", "quarterly report") },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertEquals("20", valid.arguments.getValue("limit").asContent())
    }

    @Test
    fun `an omitted argument with no default is simply absent`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject { put("query", "quarterly report") },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertFalse("scope" in valid.arguments)
    }

    @Test
    fun `a missing required argument is a typed violation naming the field`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject { put("scope", "all") },
        )

        val invalid = assertIs<SchemaValidation.Invalid>(validation)
        val violation = assertIs<SchemaViolation.MissingRequired>(invalid.violations.single())
        assertEquals("query", violation.field)
        assertTrue("query" in invalid.message)
    }

    @Test
    fun `an explicit null counts as a missing required argument`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject { put("query", null as String?) },
        )

        val invalid = assertIs<SchemaValidation.Invalid>(validation)
        assertIs<SchemaViolation.MissingRequired>(invalid.violations.single())
    }

    @Test
    fun `a value outside the enum is a typed violation carrying the permitted values`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("query", "quarterly report")
                put("scope", "archive")
            },
        )

        val invalid = assertIs<SchemaValidation.Invalid>(validation)
        val violation = assertIs<SchemaViolation.NotInEnum>(invalid.violations.single())
        assertEquals("scope", violation.field)
        assertEquals(listOf("inbox", "all"), violation.allowed.map { it.asContent() })
    }

    @Test
    fun `an enum value is accepted on its own merits when a default exists elsewhere`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("query", "quarterly report")
                put("scope", "all")
            },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertEquals("all", valid.arguments.getValue("scope").asContent())
        assertEquals("20", valid.arguments.getValue("limit").asContent())
    }

    @Test
    fun `every violation is reported and not only the first`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("scope", "archive")
                put("limit", "not a number")
            },
        )

        val invalid = assertIs<SchemaValidation.Invalid>(validation)
        assertEquals(
            listOf("query", "scope", "limit"),
            invalid.violations.map { it.field },
        )
    }

    @Test
    fun `an argument the schema does not name is dropped`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("query", "quarterly report")
                put("reasoning", "the user asked about Q3")
            },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertEquals(setOf("query", "limit"), valid.arguments.keys)
    }

    @Test
    fun `a string holding a whole number satisfies an integer`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("query", "quarterly report")
                put("limit", "7")
            },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertEquals("7", valid.arguments.getValue("limit").asContent())
    }

    @Test
    fun `a number with no fractional part satisfies an integer`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("query", "quarterly report")
                put("limit", 7.0)
            },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertEquals("7", valid.arguments.getValue("limit").asContent())
    }

    @Test
    fun `a value that cannot be read as its declared type is a typed violation`() {
        val validation = ToolArgumentSchema.validate(
            schema = THREE_FIELD_SCHEMA,
            arguments = buildJsonObject {
                put("query", "quarterly report")
                put("limit", "a few")
            },
        )

        val invalid = assertIs<SchemaValidation.Invalid>(validation)
        val violation = assertIs<SchemaViolation.WrongType>(invalid.violations.single())
        assertEquals("limit", violation.field)
        assertEquals("integer", violation.expected)
    }

    @Test
    fun `an argument with no declared type is passed through untouched`() {
        val schema = buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("payload") { put("description", "anything at all") }
            }
        }
        val payload = buildJsonArray { add(1) }

        val validation = ToolArgumentSchema.validate(
            schema = schema,
            arguments = buildJsonObject { put("payload", payload) },
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertEquals(payload, valid.arguments.getValue("payload"))
    }

    @Test
    fun `a schema with no properties declares no arguments`() {
        assertTrue(ToolArgumentSchema.declaresNoArguments(JsonObject(emptyMap())))
        assertTrue(
            ToolArgumentSchema.declaresNoArguments(
                buildJsonObject { putJsonObject("properties") {} },
            ),
        )
        assertFalse(ToolArgumentSchema.declaresNoArguments(THREE_FIELD_SCHEMA))
    }

    @Test
    fun `a schema with no properties accepts an empty argument set`() {
        val validation = ToolArgumentSchema.validate(
            schema = JsonObject(emptyMap()),
            arguments = JsonObject(emptyMap()),
        )

        val valid = assertIs<SchemaValidation.Valid>(validation)
        assertTrue(valid.arguments.isEmpty())
    }

    @Test
    fun `describe renders the name type requiredness default and permitted values`() {
        val rendered = ToolArgumentSchema.describe(THREE_FIELD_SCHEMA)
        val lines = rendered.lines()

        assertEquals(3, lines.size)
        assertEquals("- `query` (string, required) — What to search for", lines[0])
        assertEquals(
            "- `scope` (string, optional) — Where to look Allowed values: \"inbox\", \"all\".",
            lines[1],
        )
        assertEquals("- `limit` (integer, optional, default: 20) — Maximum results", lines[2])
    }

    @Test
    fun `describe says so when the schema names no arguments`() {
        assertEquals(
            "This tool takes no arguments.",
            ToolArgumentSchema.describe(JsonObject(emptyMap())),
        )
    }

    private companion object {

        /**
         * The ticket's three-field schema: `query` required, `scope` constrained by an `enum`,
         * `limit` carrying a `default`.
         */
        val THREE_FIELD_SCHEMA = buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "What to search for")
                }
                putJsonObject("scope") {
                    put("type", "string")
                    put("description", "Where to look")
                    putJsonArray("enum") {
                        add("inbox")
                        add("all")
                    }
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum results")
                    put("default", 20)
                }
            }
            putJsonArray("required") { add("query") }
        }
    }
}

/** The primitive content of a validated argument value, for a terse assertion. */
private fun JsonElement.asContent(): String = (this as JsonPrimitive).content
