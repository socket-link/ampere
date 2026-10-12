package link.socket.ampere.agents.domain.expectation

import kotlinx.serialization.Serializable

/**
 * What an agent expects to be met while a [Plan][link.socket.ampere.agents.domain.reasoning.Plan]
 * is carried out.
 *
 * `@Serializable` because `Plan` is: without it the plugin gives every plan's `expectations` an
 * open polymorphic serializer with nothing registered, so encoding *any* plan threw — unnoticed
 * until a held run's plan had to be stored (AMPR-430).
 */
@Serializable
sealed interface Expectations {

    @Serializable
    data object Blank : Expectations

    companion object {
        val blank: Expectations = Blank
    }
}
