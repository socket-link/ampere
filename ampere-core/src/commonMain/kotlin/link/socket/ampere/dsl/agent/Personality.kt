package link.socket.ampere.dsl.agent

/**
 * Personality traits that can be configured for an agent.
 * All values range from 0.0 to 1.0.
 *
 * Example:
 * ```kotlin
 * agent(Engineer) {
 *     personality {
 *         creativity = 0.8
 *         thoroughness = 0.9
 *     }
 * }
 * ```
 *
 * @param directness How direct vs diplomatic the agent communicates (0=diplomatic, 1=very direct)
 * @param creativity How creative vs conventional the agent's solutions are (0=conventional, 1=creative)
 * @param thoroughness How thorough vs concise the agent's work is (0=concise, 1=thorough)
 * @param formality How formal vs casual the communication style (0=casual, 1=formal)
 * @param riskTolerance How risk-averse vs risk-tolerant (0=conservative, 1=risk-taking)
 */
data class Personality(
    val directness: Double = 0.5,
    val creativity: Double = 0.5,
    val thoroughness: Double = 0.5,
    val formality: Double = 0.5,
    val riskTolerance: Double = 0.3,
) {
    init {
        require(directness in 0.0..1.0) { "directness must be between 0.0 and 1.0" }
        require(creativity in 0.0..1.0) { "creativity must be between 0.0 and 1.0" }
        require(thoroughness in 0.0..1.0) { "thoroughness must be between 0.0 and 1.0" }
        require(formality in 0.0..1.0) { "formality must be between 0.0 and 1.0" }
        require(riskTolerance in 0.0..1.0) { "riskTolerance must be between 0.0 and 1.0" }
    }

    companion object {
        val Default = Personality()
    }
}

/**
 * DSL builder for Personality configuration.
 */
@DslMarker
annotation class PersonalityDsl

@PersonalityDsl
class PersonalityBuilder {
    var directness: Double = 0.5
    var creativity: Double = 0.5
    var thoroughness: Double = 0.5
    var formality: Double = 0.5
    var riskTolerance: Double = 0.3

    internal fun build(): Personality = Personality(
        directness = directness,
        creativity = creativity,
        thoroughness = thoroughness,
        formality = formality,
        riskTolerance = riskTolerance,
    )
}
