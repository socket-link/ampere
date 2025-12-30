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

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Cleans an LLM response by removing markdown code blocks and whitespace.
     *
     * LLMs often wrap JSON responses in ```json ... ``` blocks, which need
     * to be stripped before parsing.
     *
     * @param response The raw LLM response
     * @return The cleaned response suitable for JSON parsing
     */
    fun cleanJsonResponse(response: String): String {
        return response
            .trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
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
