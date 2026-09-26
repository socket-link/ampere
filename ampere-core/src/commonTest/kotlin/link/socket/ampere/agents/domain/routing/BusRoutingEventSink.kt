package link.socket.ampere.agents.domain.routing

import link.socket.ampere.agents.events.bus.EventSerialBus

/**
 * A [RoutingEventSink] that dispatches straight onto this bus, for tests that observe a
 * relay's routing events without a store. Only a test inside `ampere-core` can build this:
 * `EventSerialBus.publish` is `internal` (AMPR-340), and production relays get their sink
 * from [routingEventSink] over a door.
 */
fun EventSerialBus.busRoutingEventSink(): RoutingEventSink = { event, _ -> publish(event) }
