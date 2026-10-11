package link.socket.ampere.agents.domain.emission.kinds.recipe

/**
 * A carbonara with mixed units, a countable (null-unit) ingredient, tokenised
 * steps and a timer — the shape AMPR-427's validation asks for.
 */
internal fun carbonara(): RecipePayload = RecipePayload(
    title = "Spaghetti carbonara",
    description = "Roman, no cream.",
    baseServings = 4,
    ingredients = listOf(
        RecipeIngredient(id = "spaghetti", name = "Spaghetti", amount = 400.0, unit = RecipeUnit.GRAM),
        RecipeIngredient(id = "guanciale", name = "Guanciale", amount = 150.0, unit = RecipeUnit.GRAM),
        RecipeIngredient(id = "pecorino", name = "Pecorino Romano", amount = 1.0, unit = RecipeUnit.CUP),
        RecipeIngredient(id = "eggs", name = "Egg yolks", amount = 4.0, unit = null),
        RecipeIngredient(id = "pepper", name = "Black pepper", amount = 1.0, unit = RecipeUnit.TEASPOON),
        RecipeIngredient(id = "salt", name = "Salt", amount = 1.0, unit = RecipeUnit.PINCH),
    ),
    steps = listOf(
        RecipeStep(
            id = "boil",
            title = "Boil the pasta",
            content = "Cook {spaghetti} in salted water until al dente.",
            timerSeconds = 540,
        ),
        RecipeStep(
            id = "render",
            title = "Render the guanciale",
            content = "Fry {guanciale} over medium heat until crisp.",
        ),
        RecipeStep(
            id = "toss",
            title = "Toss",
            content = "Off the heat, toss the pasta with {eggs}, {pecorino} and {pepper}.",
        ),
    ),
    notes = "Keep the pan off the heat so the eggs don't scramble.",
)
