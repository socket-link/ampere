package link.socket.ampere.agents.domain.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Utilities for parsing LLM responses, handling common patterns like
 * markdown code blocks and JSON extraction.
 */
object LLMResponseParser {

    private const val FENCE = "```"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Cleans an LLM response down to the JSON value it carries.
     *
     * Models often wrap the JSON they were asked for in a ```` ```json ```` fence,
     * and some keep writing after it — an explanation, a list of reasons. The
     * rule, in order:
     *
     * 1. Trim the reply and strip one leading ```` ```json ````, ```` ```JSON ````
     *    or ```` ``` ```` and one trailing ```` ``` ````. If that parses as JSON it
     *    is returned unchanged, so every reply that parsed before the extraction
     *    rule existed comes back byte for byte.
     * 2. Otherwise, if the reply holds a fenced block, the first `{` or `[` inside
     *    the **first** fence is scanned to its matching close; then the same scan
     *    runs over the whole reply. The scan tracks string literals and backslash
     *    escapes, so a brace or bracket inside a string does not count. The first
     *    scanned value that parses is returned.
     * 3. If nothing parses, the step-1 result is returned, so the caller's parse
     *    fails with the same exception it always did.
     *
     * The first value wins: a reply that quotes an example object before its
     * real answer is read as the example. That is the cost of not guessing.
     *
     * @param response The raw LLM response
     * @return The cleaned response suitable for JSON parsing
     */
    fun cleanJsonResponse(response: String): String {
        val stripped = response
            .trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (parses(stripped)) return stripped

        val candidates = listOfNotNull(firstFencedBlock(response), response)
        return candidates.firstNotNullOfOrNull { text -> firstBalancedValue(text)?.takeIf(::parses) }
            ?: stripped
    }

    private fun parses(candidate: String): Boolean =
        runCatching { json.parseToJsonElement(candidate) }.isSuccess

    /** The body of the first ```` ``` ```` fence, to its closing fence or the end of the reply. */
    private fun firstFencedBlock(response: String): String? {
        val open = response.indexOf(FENCE)
        if (open < 0) return null
        var start = open + FENCE.length
        if (response.startsWith("json", start) || response.startsWith("JSON", start)) start += "json".length
        val close = response.indexOf(FENCE, start)
        return if (close < 0) response.substring(start) else response.substring(start, close)
    }

    /**
     * The text from the first `{` or `[` to its matching close, or null when it
     * never closes. Braces and brackets inside string literals are skipped.
     */
    private fun firstBalancedValue(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    /**
     * Parses a cleaned JSON string into a JsonObject.
     *
     * @param jsonString The JSON string to parse
     * @return The parsed JsonObject
     * @throws IllegalStateException if parsing fails or result is not an object
     */
    fun parseJsonObject(jsonString: String): JsonObject {
        val element = json.parseToJsonElement(jsonString)
        return element as? JsonObject
            ?: throw IllegalStateException("Expected JSON object but got ${element::class.simpleName}")
    }

    /**
     * Parses a cleaned JSON string into a JsonArray.
     *
     * @param jsonString The JSON string to parse
     * @return The parsed JsonArray
     * @throws IllegalStateException if parsing fails or result is not an array
     */
    fun parseJsonArray(jsonString: String): JsonArray {
        val element = json.parseToJsonElement(jsonString)
        return element as? JsonArray
            ?: throw IllegalStateException("Expected JSON array but got ${element::class.simpleName}")
    }

    /**
     * Parses a cleaned JSON string into a JsonElement.
     *
     * @param jsonString The JSON string to parse
     * @return The parsed JsonElement
     */
    fun parseJsonElement(jsonString: String): JsonElement {
        return json.parseToJsonElement(jsonString)
    }

    /**
     * Safely extracts a string value from a JsonObject.
     *
     * @param obj The JsonObject to extract from
     * @param key The key to look up
     * @param default Default value if key is missing or null
     * @return The extracted string or default
     */
    fun getString(obj: JsonObject, key: String, default: String = ""): String {
        return obj[key]?.let { element ->
            when {
                element is kotlinx.serialization.json.JsonPrimitive && element.isString -> element.content
                element is kotlinx.serialization.json.JsonPrimitive -> element.content
                else -> default
            }
        } ?: default
    }

    /**
     * Safely extracts an integer value from a JsonObject.
     *
     * @param obj The JsonObject to extract from
     * @param key The key to look up
     * @param default Default value if key is missing or not an integer
     * @return The extracted integer or default
     */
    fun getInt(obj: JsonObject, key: String, default: Int = 0): Int {
        return obj[key]?.let { element ->
            if (element is kotlinx.serialization.json.JsonPrimitive) {
                element.content.toIntOrNull() ?: default
            } else {
                default
            }
        } ?: default
    }

    /**
     * Safely extracts a boolean value from a JsonObject.
     *
     * @param obj The JsonObject to extract from
     * @param key The key to look up
     * @param default Default value if key is missing
     * @return The extracted boolean or default
     */
    fun getBoolean(obj: JsonObject, key: String, default: Boolean = false): Boolean {
        return obj[key]?.let { element ->
            if (element is kotlinx.serialization.json.JsonPrimitive) {
                element.content.toBooleanStrictOrNull() ?: default
            } else {
                default
            }
        } ?: default
    }

    /**
     * Safely extracts a JsonArray from a JsonObject.
     *
     * @param obj The JsonObject to extract from
     * @param key The key to look up
     * @return The extracted JsonArray or null
     */
    fun getArray(obj: JsonObject, key: String): JsonArray? {
        return obj[key] as? JsonArray
    }

    /**
     * Safely extracts a JsonObject from a JsonObject.
     *
     * @param obj The JsonObject to extract from
     * @param key The key to look up
     * @return The extracted JsonObject or null
     */
    fun getObject(obj: JsonObject, key: String): JsonObject? {
        return obj[key] as? JsonObject
    }
}
