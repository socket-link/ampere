package link.socket.ampere.agents.domain.expectation

sealed interface Expectations {

    data object Blank : Expectations

    companion object {
        val blank: Expectations = Blank
    }
}
