package link.socket.ampere.agents.execution.dispatch

/**
 * The model and reasoning effort an agent run is launched with.
 *
 * @property fromTicket Which halves came from the ticket's recommendation line rather
 *   than the configured default — so a run report can say "the ticket asked for this"
 *   apart from "nobody asked".
 */
data class ModelChoice(
    val model: String,
    val effort: String,
    val fromTicket: Set<Half> = emptySet(),
) {
    /** The two halves of a recommendation. */
    enum class Half { MODEL, EFFORT }
}

/**
 * Reads a ticket's model/effort recommendation line (the AMPR-306 convention from
 * the AMPR-288 dispatch ethnography): the bolded first line of the description.
 *
 * ```
 * **Model/effort recommendation:** Opus (or stronger, e.g. Fable), high — this is…
 * ```
 *
 * The rationale after the dash is for a person and is ignored; so is anything in
 * parentheses, which is where a line hedges ("or stronger, e.g. Fable") rather than
 * recommends. Of what remains, the first model family named is the model and the
 * first effort level named is the effort. Either half the line does not name falls
 * back to the configured default, separately — a line that names a model and no
 * effort still gets its model.
 *
 * Only the first non-blank line is read. That is where the convention puts it, and a
 * scan further down would find model names in the ticket's own prose.
 */
object ModelRecommendations {

    /** The bolded label the line opens with. */
    const val LINE_PREFIX: String = "**Model/effort recommendation:**"

    /** Model families, as the launched agent's CLI names them. */
    val MODELS: List<String> = listOf("fable", "opus", "sonnet", "haiku")

    /** Effort levels, as the launched agent's CLI names them. */
    val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

    /** The choice for a ticket with [description], falling back to [default] half by half. */
    fun resolve(description: String?, default: ModelChoice): ModelChoice {
        val words = recommendationWords(description) ?: return default.copy(fromTicket = emptySet())
        val model = words.firstOrNull { it in MODELS }
        val effort = words.firstOrNull { it in EFFORTS }
        return ModelChoice(
            model = model ?: default.model,
            effort = effort ?: default.effort,
            fromTicket = buildSet {
                if (model != null) add(ModelChoice.Half.MODEL)
                if (effort != null) add(ModelChoice.Half.EFFORT)
            },
        )
    }

    /** The lowercase words of the recommendation clause, or null when there is no line. */
    private fun recommendationWords(description: String?): List<String>? {
        val first = description?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (!first.startsWith(LINE_PREFIX, ignoreCase = true)) return null

        val clause = first.substring(LINE_PREFIX.length)
            .split(RATIONALE_SEPARATOR, limit = 2)
            .first()
            .replace(PARENTHETICAL, " ")
        return clause.lowercase().split(NON_WORD).filter { it.isNotEmpty() }
    }

    /** An em or en dash, or a spaced hyphen: where the rationale starts. */
    private val RATIONALE_SEPARATOR = Regex("""\s[—–-]\s""")

    private val PARENTHETICAL = Regex("""\([^)]*\)""")

    private val NON_WORD = Regex("""[^a-z]+""")
}
