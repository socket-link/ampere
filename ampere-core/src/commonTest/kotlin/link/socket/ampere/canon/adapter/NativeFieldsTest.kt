package link.socket.ampere.canon.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativeSchema

class NativeFieldsTest {
    private val schema = NativeSchema("TestEntity")
    private val canonType = CanonType.CALENDAR_EVENT

    private fun <E> read(
        fields: JsonObject,
        block: (NativeFields) -> E,
    ): Result<E> = NativeFields.project(fields, canonType, schema, block)

    private fun failureOf(result: Result<*>): CanonConversionFailure {
        val error = result.exceptionOrNull()
        assertIs<CanonConversionException>(error)
        return error.failure
    }

    // -----------------------------------------------------------------
    // The contract that matters most: the internal throw never escapes
    // -----------------------------------------------------------------

    @Test
    fun `a missing required field becomes a Result failure rather than a thrown exception`() {
        // The reader throws internally so projections can read fields inline instead of
        // `?: return canonFailure(...)`. `project` is the only entry point precisely so that
        // throw cannot reach caller code — this is the test that pins it.
        val result = read(JsonObject(emptyMap())) { it.requireString("title") }

        assertTrue(result.isFailure)
        val failure = failureOf(result)
        assertIs<CanonConversionFailure.MissingRequiredField>(failure)
        assertEquals("title", failure.field)
        assertEquals(schema, failure.schema)
    }

    @Test
    fun `an unrelated exception is not swallowed`() {
        // Only CanonConversionException is converted. A genuine bug in a projection must still
        // surface as a crash rather than being laundered into a typed canon failure.
        val thrown =
            runCatching {
                read(JsonObject(emptyMap())) { error("bug in projection") }
            }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        assertEquals("bug in projection", thrown.message)
    }

    // -----------------------------------------------------------------
    // Required reads
    // -----------------------------------------------------------------

    @Test
    fun `required reads return values when present`() {
        val fields =
            buildJsonObject {
                put("name", JsonPrimitive("Ada"))
                put("at", JsonPrimitive(1_700_000_000_000))
                put("score", JsonPrimitive(1.5))
                put("ok", JsonPrimitive(true))
            }

        val result =
            read(fields) {
                listOf(
                    it.requireString("name"),
                    it.requireEpochMillis("at"),
                    it.requireDouble("score"),
                    it.requireBoolean("ok"),
                )
            }.getOrThrow()

        assertEquals("Ada", result[0])
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_000), result[1])
        assertEquals(1.5, result[2])
        assertEquals(true, result[3])
    }

    @Test
    fun `Int and Long reads cover the numeric shapes Double and epoch-millis don't`() {
        // widthPixels/unreadCount are plain Int; sizeBytes is a plain Long — neither is a
        // date, so requireEpochMillis is the wrong reader and requireDouble would silently
        // truncate a value a provider meant to be exact.
        val fields =
            buildJsonObject {
                put("widthPixels", JsonPrimitive(1920))
                put("sizeBytes", JsonPrimitive(4_294_967_296L))
            }

        val result =
            read(fields) {
                it.requireInt("widthPixels") to it.requireLong("sizeBytes")
            }.getOrThrow()

        assertEquals(1920, result.first)
        assertEquals(4_294_967_296L, result.second)
    }

    @Test
    fun `a present but unreadable value is malformed rather than missing`() {
        // The distinction matters: "absent" is often legitimate, "present in the wrong shape"
        // means the provider changed and someone should look.
        val failure =
            failureOf(read(buildJsonObject { put("at", JsonPrimitive("nope")) }) { it.requireEpochMillis("at") })

        assertIs<CanonConversionFailure.MalformedField>(failure)
        assertEquals("at", failure.field)
    }

    // -----------------------------------------------------------------
    // Optional reads
    // -----------------------------------------------------------------

    @Test
    fun `absent and JSON null read the same as not set`() {
        val fields = buildJsonObject { put("maybe", JsonNull) }

        assertNull(read(fields) { it.optionalString("maybe") }.getOrThrow())
        assertNull(read(JsonObject(emptyMap())) { it.optionalString("maybe") }.getOrThrow())
        assertNull(read(fields) { it.optionalEpochMillis("maybe") }.getOrThrow())
        assertNull(read(fields) { it.optionalDouble("maybe") }.getOrThrow())
        assertNull(read(fields) { it.optionalInt("maybe") }.getOrThrow())
        assertNull(read(fields) { it.optionalLong("maybe") }.getOrThrow())
        assertNull(read(fields) { it.optionalBoolean("maybe") }.getOrThrow())
    }

    @Test
    fun `optionalBoolean falls back to the supplied default`() {
        assertEquals(true, read(JsonObject(emptyMap())) { it.optionalBoolean("flag", default = true) }.getOrThrow())
        assertEquals(
            false,
            read(buildJsonObject { put("flag", JsonPrimitive(false)) }) {
                it.optionalBoolean("flag", default = true)
            }.getOrThrow(),
        )
    }

    @Test
    fun `an optional read of the wrong kind still fails`() {
        // Reading an object where a string was expected must not quietly become null, or a
        // provider reshaping a field looks identical to the user never setting it.
        val fields = buildJsonObject { put("name", buildJsonObject { put("first", JsonPrimitive("Ada")) }) }

        val failure = failureOf(read(fields) { it.optionalString("name") })

        assertIs<CanonConversionFailure.MalformedField>(failure)
    }

    // -----------------------------------------------------------------
    // Nesting — the reason failures carry a path
    // -----------------------------------------------------------------

    @Test
    fun `nested failures report the full path`() {
        val fields =
            buildJsonObject {
                put("place", buildJsonObject { put("latitude", JsonPrimitive("not-a-number")) })
            }

        val failure = failureOf(read(fields) { it.requireObject("place").requireDouble("latitude") })

        assertIs<CanonConversionFailure.MalformedField>(failure)
        assertEquals("place.latitude", failure.field)
    }

    @Test
    fun `array member failures report their index`() {
        val fields =
            buildJsonObject {
                put(
                    "people",
                    buildJsonArray {
                        add(buildJsonObject { put("name", JsonPrimitive("Ada")) })
                        add(buildJsonObject { })
                    },
                )
            }

        val failure =
            failureOf(read(fields) { reader -> reader.objectArray("people").map { it.requireString("name") } })

        assertIs<CanonConversionFailure.MissingRequiredField>(failure)
        assertEquals("people[1].name", failure.field)
    }

    @Test
    fun `objectArray is empty when absent and skips non-object members`() {
        assertTrue(read(JsonObject(emptyMap())) { it.objectArray("people") }.getOrThrow().isEmpty())

        val mixed =
            buildJsonObject {
                put(
                    "people",
                    buildJsonArray {
                        add(buildJsonObject { put("name", JsonPrimitive("Ada")) })
                        add(JsonPrimitive("not an object"))
                    },
                )
            }

        // A provider mixing shapes in one array is a quirk, not a corrupt record: read what
        // parses rather than failing the whole projection.
        val names = read(mixed) { reader -> reader.objectArray("people").map { it.requireString("name") } }.getOrThrow()
        assertEquals(listOf("Ada"), names)
    }

    @Test
    fun `a present non-array where an array was expected fails`() {
        val failure = failureOf(read(buildJsonObject { put("people", JsonPrimitive(3)) }) { it.objectArray("people") })

        assertIs<CanonConversionFailure.MalformedField>(failure)
        assertEquals("people", failure.field)
    }

    @Test
    fun `a caller can fail the projection with its own reason`() {
        val fields = buildJsonObject { put("kind", JsonPrimitive("unheard-of")) }

        val failure =
            failureOf(
                read(fields) { reader ->
                    val kind = reader.requireString("kind")
                    if (kind != "known") reader.malformed<String>("kind", "no DocumentKind maps to '$kind'") else kind
                },
            )

        assertIs<CanonConversionFailure.MalformedField>(failure)
        assertTrue(failure.reason.contains("unheard-of"))
    }

    @Test
    fun `contains distinguishes an explicitly null field from an absent one`() {
        val fields = buildJsonObject { put("maybe", JsonNull) }

        assertTrue(read(fields) { it.contains("maybe") }.getOrThrow())
        assertTrue(!read(fields) { it.contains("other") }.getOrThrow())
    }
}
