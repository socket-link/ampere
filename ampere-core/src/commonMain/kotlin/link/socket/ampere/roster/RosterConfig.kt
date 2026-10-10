package link.socket.ampere.roster

import kotlinx.serialization.Serializable

/**
 * A roster a consumer authors, as data (AMPR-409).
 *
 * [Roster] is an interface so that a consumer whose seats are computed — from a
 * catalog, from a plug's manifest — can hand one over without flattening it first.
 * `RosterConfig` is the other case, where there is nothing to compute: a host and a
 * list of seats, serializable, so a roster can be declared in the same file as the
 * rest of a consumer's workflow and read back.
 *
 * It names no verifier and no resolver, so a Room bound to it opens no verdict and no
 * hazard threads — the seats talk, nobody convicts. A consumer that wants a reviewing
 * seat implements [Roster] and overrides `verifier` and `resolverFor`; nothing else on
 * the interface is abstract.
 *
 * Review edges are not checked here. [reviewsAreAcyclic] is a question about a list of
 * roles, asked by whoever is about to rely on the answer, and a roster is a legitimate
 * value while its edges are still being filled in.
 *
 * @property host The role that hosts the Room. Must be one of [roles].
 * @property roles Every seat, in the order a reader should see them. Ids are distinct.
 */
@Serializable
data class RosterConfig(
    override val host: RoleId,
    val roles: List<RoleConfig>,
) : Roster {

    init {
        val duplicated = roles.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "a roster names each role once; duplicated: ${duplicated.joinToString { it.value }}"
        }
        require(roles.any { it.id == host }) { "host '${host.value}' is not one of the roles" }
    }

    override fun all(): List<RoleConfig> = roles
}
