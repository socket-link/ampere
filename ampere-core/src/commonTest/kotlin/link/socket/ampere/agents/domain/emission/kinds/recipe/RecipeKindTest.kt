package link.socket.ampere.agents.domain.emission.kinds.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.EmissionProvenance
import link.socket.ampere.agents.domain.emission.inputDigest

class RecipeKindTest {

    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private fun emissionOf(payload: EmissionPayload.Domain): Emission = Emission(
        id = "emission-recipe",
        kind = EmissionKind.Domain(payload.kindId),
        payload = payload,
        provenance = EmissionProvenance(
            inputDigest = inputDigest(payload),
            parentEmissionId = null,
            principal = Principal.Ambient,
        ),
        producedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    @Test
    fun `a recipe Emission round-trips through the Emission serializer`() {
        val recipe = carbonara()
        val original = emissionOf(RecipeKind.encode(recipe))

        val encoded = json.encodeToString(Emission.serializer(), original)
        val decoded = json.decodeFromString(Emission.serializer(), encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"EmissionKind.Domain\""), encoded)
        assertTrue(encoded.contains("\"type\":\"EmissionPayload.Domain\""), encoded)
        assertTrue(encoded.contains("\"kindId\":\"recipe\""), encoded)
        assertEquals(recipe, RecipeKind.decodeOrNull(decoded.payload))
    }

    @Test
    fun `the body is the recipe under its own stable serial names`() {
        val body = RecipeKind.encode(carbonara()).body.jsonObject

        assertEquals("Spaghetti carbonara", body.getValue("title").jsonPrimitive.content)
        val ingredients = body.getValue("ingredients").jsonArray.map { it.jsonObject }
        assertEquals("eggs", ingredients[3].getValue("id").jsonPrimitive.content)
        assertEquals(JsonNull, ingredients[3].getValue("unit"))
        assertEquals("GRAM", ingredients[0].getValue("unit").jsonPrimitive.content)
        assertEquals("Recipe.Payload", RecipePayload.serializer().descriptor.serialName)
        assertEquals("Recipe.Ingredient", RecipeIngredient.serializer().descriptor.serialName)
        assertEquals("Recipe.Step", RecipeStep.serializer().descriptor.serialName)
        assertEquals("Recipe.Unit", RecipeUnit.serializer().descriptor.serialName)
    }

    @Test
    fun `an unknown domain kind still decodes with its fallback text and the recipe descriptor skips it`() {
        val mealPlan = EmissionPayload.Domain(
            kindId = "meal-plan",
            schemaVersion = 1,
            body = buildJsonObject { put("days", JsonPrimitive(7)) },
            fallbackText = "A 7-day meal plan",
        )
        val encoded = json.encodeToString(Emission.serializer(), emissionOf(mealPlan))

        val decoded = json.decodeFromString(Emission.serializer(), encoded)

        val payload = assertIs<EmissionPayload.Domain>(decoded.payload)
        assertEquals("meal-plan", payload.kindId)
        assertEquals("A 7-day meal plan", payload.fallbackText)
        assertEquals(EmissionKind.Domain("meal-plan"), decoded.kind)
        assertNull(RecipeKind.decodeOrNull(payload))
    }

    @Test
    fun `the input digest is deterministic for equal recipes and differs when an amount changes`() {
        val a = inputDigest(RecipeKind.encode(carbonara()))
        val b = inputDigest(RecipeKind.encode(carbonara()))
        assertEquals(a, b)

        val moreGuanciale = carbonara().let { recipe ->
            recipe.copy(
                ingredients = recipe.ingredients.map {
                    if (it.id == "guanciale") it.copy(amount = 200.0) else it
                },
            )
        }
        assertNotEquals(a, inputDigest(RecipeKind.encode(moreGuanciale)))
    }

    @Test
    fun `fallback text names the recipe and its size`() {
        assertEquals(
            "Spaghetti carbonara (6 ingredients, 3 steps)",
            RecipeKind.encode(carbonara()).fallbackText,
        )
    }

    @Test
    fun `a step token naming an undeclared ingredient is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            carbonara().let { recipe ->
                recipe.copy(
                    steps = recipe.steps + RecipeStep(id = "garnish", title = "Garnish", content = "Add {parsley}."),
                )
            }
        }
        assertTrue(failure.message!!.contains("parsley"), failure.message)
    }

    @Test
    fun `duplicate ingredient ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RecipePayload(
                title = "Toast",
                ingredients = listOf(
                    RecipeIngredient("bread", "Bread", 2.0),
                    RecipeIngredient("bread", "More bread", 1.0),
                ),
                steps = emptyList(),
            )
        }
    }

    @Test
    fun `base servings below one is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RecipePayload(title = "Nothing", baseServings = 0, ingredients = emptyList(), steps = emptyList())
        }
    }

    @Test
    fun `a negative amount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RecipeIngredient(id = "salt", name = "Salt", amount = -1.0, unit = RecipeUnit.PINCH)
        }
    }

    @Test
    fun `a zero timer is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RecipeStep(id = "rest", title = "Rest", content = "Wait.", timerSeconds = 0)
        }
    }

    @Test
    fun `a body missing a defaulted field decodes with the default`() {
        // A v1 body written before `notes` existed would simply lack the key.
        val sparse = RecipeKind.encode(carbonara()).let { payload ->
            payload.copy(body = JsonObject(payload.body.jsonObject - "notes"))
        }

        val decoded = RecipeKind.decodeOrNull(sparse)

        assertEquals(carbonara().copy(notes = null), decoded)
    }
}
