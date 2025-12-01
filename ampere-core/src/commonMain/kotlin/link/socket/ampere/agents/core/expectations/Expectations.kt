package link.socket.ampere.agents.core.expectations

sealed interface Expectations {

    data object Blank : Expectations

    companion object {
        val blank: Expectations = Blank
    }
}
