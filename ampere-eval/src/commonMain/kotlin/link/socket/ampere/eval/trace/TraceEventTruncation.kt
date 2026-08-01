package link.socket.ampere.eval.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Appended to any string leaf cut by [truncateStringLeaves], so a truncated
 * field is recognizable in place rather than silently shortened. */
const val TRUNCATION_MARKER = "…[truncated]"

/**
 * The shared `DEFAULT_JSON`'s `classDiscriminator` (see `RepositoryFactory.kt`).
 * [truncateStringLeaves] must never cut this key's value: it is what
 * `Event.serializer()` reads to pick the polymorphic subclass on decode, and
 * truncating it produces a class name that resolves to nothing, hard-failing
 * decode — the exact "unreplayable trace" failure AMPR-267 forbids.
 */
private const val CLASS_DISCRIMINATOR_KEY = "type"

/**
 * Truncates every string leaf in [this] longer than [maxChars], generically,
 * without knowledge of the owning `Event` subtype's shape (AMPR-267).
 *
 * `Event` is a sealed *interface* spread across ~24 files; bounding every
 * unbounded String field (description, context, prompt, preview, ...)
 * per-subtype would mean touching all of them today and re-touching every new
 * one. Walking the already-serialized [JsonElement] tree instead bounds every
 * current and future free-text field from one place, and never changes the
 * JSON shape (keys, object/array nesting) — only string leaf *values* shrink,
 * and [CLASS_DISCRIMINATOR_KEY] is never touched — so truncated output still
 * decodes via `Event.serializer()`.
 *
 * Returns the possibly-truncated tree paired with whether any leaf was cut.
 */
fun JsonElement.truncateStringLeaves(maxChars: Int): Pair<JsonElement, Boolean> {
    var truncated = false

    fun walk(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                put(key, if (key == CLASS_DISCRIMINATOR_KEY) value else walk(value))
            }
        }
        is JsonArray -> buildJsonArray { element.forEach { add(walk(it)) } }
        is JsonNull -> element
        is JsonPrimitive -> {
            if (element.isString && element.content.length > maxChars) {
                truncated = true
                JsonPrimitive(element.content.take(maxChars) + TRUNCATION_MARKER)
            } else {
                element
            }
        }
    }

    return walk(this) to truncated
}

/** Serialized UTF-8 byte size of [this] under [json]. */
fun JsonElement.serializedByteSize(json: Json): Int =
    json.encodeToString(JsonElement.serializer(), this).encodeToByteArray().size
