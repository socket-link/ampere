package link.socket.ampere.plug.spi

class ReadyQueueSourceContractTest : PerceiveSourceContract<FixtureTicket>() {

    override fun source(): PerceiveSource<FixtureTicket> = ReadyQueueSource(
        tickets = listOf(
            FixtureTicket("AMPR-1", labels = setOf("wave:w0")),
            FixtureTicket("AMPR-2", labels = setOf("wave:w0"), openBlockers = setOf("AMPR-9")),
            FixtureTicket("AMPR-3", labels = setOf("wave:w0", "gate:awaiting-verdict")),
            FixtureTicket("AMPR-4", labels = setOf("wave:w1")),
            FixtureTicket("AMPR-5"),
        ),
    )

    override fun fixturePredicates(): List<PerceivePredicate> = readyQueueRule
}
