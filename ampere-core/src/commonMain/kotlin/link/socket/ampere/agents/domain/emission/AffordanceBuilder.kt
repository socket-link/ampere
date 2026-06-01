package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.util.randomUUID

/**
 * Builder for assembling [Affordance] lists in DSL `ask` / `askHuman` calls.
 *
 * Provides two kinds of affordance:
 * - [affordance] — a structured option with an explicit label and payload
 * - [freeTextAffordance] — a free-text entry field; the human's typed or
 *   spoken text is delivered back as [EmissionEvent.Resolved.replyContext].
 */
class AffordanceBuilder {

    private val affordances = mutableListOf<Affordance>()

    /** Add a structured option with the given [label] and an opaque [signalPayload]. */
    fun affordance(
        label: String,
        id: AffordanceId = randomUUID(),
        signalPayload: kotlinx.serialization.json.JsonElement = JsonPrimitive(label),
    ): Affordance = Affordance(id, label, signalPayload).also { affordances.add(it) }

    /**
     * Add a free-text entry affordance.
     *
     * The human's response text is delivered back in
     * [EmissionEvent.Resolved.replyContext] as a JSON object:
     * `{"type":"free-text","text":"<user input>"}`.
     *
     * Renderers should display this as a text-input field; voice surfaces
     * treat the next utterance as the reply rather than requiring an
     * affordance-label match.
     */
    fun freeTextAffordance(prompt: String): Affordance {
        val id = "free-text-${randomUUID()}"
        val payload = JsonObject(
            mapOf(
                "type" to JsonPrimitive("free-text"),
                "prompt" to JsonPrimitive(prompt),
            ),
        )
        return Affordance(id, prompt, payload).also { affordances.add(it) }
    }

    internal fun build(): List<Affordance> = affordances.toList()
}

/** Extracts free-text reply from a [replyContext] produced by [AffordanceBuilder.freeTextAffordance]. */
fun extractFreeText(replyContext: kotlinx.serialization.json.JsonElement?): String? {
    val obj = replyContext as? JsonObject ?: return null
    if ((obj["type"] as? JsonPrimitive)?.content != "free-text") return null
    return (obj["text"] as? JsonPrimitive)?.content
}
