package link.socket.ampere.agents.domain.emission.kinds.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.emission.kinds.DomainEmissionKind

/**
 * The first domain Emission kind: a recipe a renderer can scale by servings
 * and time by step. AMPERE carries the data and never renders it — a
 * servings dial, ingredient scaling and step timers are the consumer's.
 *
 * Publish with `present(RecipeKind, recipe)`; read with
 * `RecipeKind.decodeOrNull(payload)`.
 */
val RecipeKind: DomainEmissionKind<RecipePayload> = DomainEmissionKind(
    id = "recipe",
    schemaVersion = 1,
    serializer = RecipePayload.serializer(),
    fallbackText = { recipe ->
        "${recipe.title} (${recipe.ingredients.size} ingredients, ${recipe.steps.size} steps)"
    },
)

/**
 * A recipe. Amounts in [ingredients] are for [baseServings]; a renderer
 * scales them linearly for the servings the person picks.
 *
 * Validated at construction (and so on decode):
 * - ingredient ids are unique, and so are step ids;
 * - every `{token}` in a step's [RecipeStep.content] names a declared ingredient id;
 * - [baseServings] is at least 1;
 * - every [RecipeIngredient.amount] is non-negative;
 * - every [RecipeStep.timerSeconds], when set, is positive.
 */
@Serializable
@SerialName("Recipe.Payload")
data class RecipePayload(
    val title: String,
    val description: String? = null,
    val baseServings: Int = 4,
    val ingredients: List<RecipeIngredient>,
    val steps: List<RecipeStep>,
    val notes: String? = null,
) {
    init {
        require(title.isNotBlank()) { "Recipe title must not be blank" }
        require(baseServings >= 1) { "baseServings must be >= 1, got $baseServings" }
        val ingredientIds = ingredients.map { it.id }
        val duplicateIngredients = ingredientIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIngredients.isEmpty()) { "Duplicate ingredient ids: $duplicateIngredients" }
        val duplicateSteps = steps.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicateSteps.isEmpty()) { "Duplicate step ids: $duplicateSteps" }
        val declared = ingredientIds.toSet()
        for (step in steps) {
            val undeclared = INGREDIENT_TOKEN.findAll(step.content)
                .map { it.groupValues[1] }
                .filter { it !in declared }
                .toList()
            require(undeclared.isEmpty()) {
                "Step '${step.id}' references undeclared ingredient(s): $undeclared"
            }
        }
    }

    private companion object {
        /** `{ingredientId}` tokens a renderer substitutes with the scaled ingredient. */
        val INGREDIENT_TOKEN = Regex("""\{([^{}]*)\}""")
    }
}

/**
 * One ingredient. A `null` [unit] means a countable item ("3 garlic cloves").
 *
 * @property id the token name steps use to reference it (`{garlic}`).
 * @property amount for [RecipePayload.baseServings]; `>= 0`.
 */
@Serializable
@SerialName("Recipe.Ingredient")
data class RecipeIngredient(
    val id: String,
    val name: String,
    val amount: Double,
    val unit: RecipeUnit? = null,
) {
    init {
        require(id.isNotBlank()) { "Ingredient id must not be blank" }
        require(amount >= 0) { "Ingredient '$id' amount must be >= 0, got $amount" }
    }
}

/**
 * One step. [content] may contain `{ingredientId}` tokens, which the renderer
 * substitutes with the (scaled) ingredient; AMPERE does not render.
 *
 * @property timerSeconds a timer the renderer offers for this step, `> 0` when set.
 */
@Serializable
@SerialName("Recipe.Step")
data class RecipeStep(
    val id: String,
    val title: String,
    val content: String,
    val timerSeconds: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "Step id must not be blank" }
        timerSeconds?.let { require(it > 0) { "Step '$id' timerSeconds must be > 0, got $it" } }
    }
}

/** Units an ingredient amount can be expressed in. Countable items carry no unit. */
@Serializable
@SerialName("Recipe.Unit")
enum class RecipeUnit {
    GRAM,
    KILOGRAM,
    MILLILITER,
    LITER,
    TEASPOON,
    TABLESPOON,
    CUP,
    FLUID_OUNCE,
    OUNCE,
    POUND,
    PINCH,
}
