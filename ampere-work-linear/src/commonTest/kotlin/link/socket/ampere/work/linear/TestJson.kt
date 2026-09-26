package link.socket.ampere.work.linear

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The text of a recorded call argument, for request-level assertions. */
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The text members of a recorded array argument. */
fun JsonObject.texts(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
