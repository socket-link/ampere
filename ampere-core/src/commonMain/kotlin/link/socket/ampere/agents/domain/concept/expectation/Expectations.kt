package link.socket.ampere.agents.domain.concept.expectation

sealed interface Expectations {

    data object Blank : Expectations

    companion object {
        val blank: Expectations = Blank
    }
}
