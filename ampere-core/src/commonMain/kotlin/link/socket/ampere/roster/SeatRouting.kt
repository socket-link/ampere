package link.socket.ampere.roster

import link.socket.ampere.agents.domain.routing.ExecutionTags
import link.socket.ampere.agents.domain.routing.RoutingContext

/**
 * This context with [seat]'s [RoleConfig.execution] on it (AMPR-413).
 *
 * The one seam between a roster and the relay. Whatever fills a seat — the hosted run
 * (AMPR-393), a consumer's own loop — builds its `RoutingContext` the way it already
 * does and passes it through here before the call, so a seat's declared model and
 * effort are on every call made as that seat and on no other seat's.
 *
 * The seat's tags are added to whatever the context already carries; nothing is
 * replaced, because the phase, the agent and the capability requirement on a call are
 * facts about the call, not about the seat. A seat that declares no execution returns
 * the context unchanged — not one carrying empty tags — so a call made as a seat with
 * no preference routes exactly as the same call made outside a roster, which is what
 * "the runner's own default applies" has to mean.
 */
fun RoutingContext.withSeat(seat: RoleConfig): RoutingContext =
    seat.execution?.let { execution -> copy(tags = tags + ExecutionTags.of(execution)) } ?: this
