package link.socket.ampere.roster

import kotlinx.serialization.Serializable

/**
 * A roster a consumer authors, as data (AMPR-409, AMPR-413).
 *
 * [Roster] is an interface so that a consumer whose seats are computed — from a
 * catalog, from a plug's manifest — can hand one over without flattening it first.
 * `RosterConfig` is the other case, where there is nothing to compute: a host, a list
 * of seats and optionally the seat that reviews them, serializable, so a roster can be
 * declared in the same file as the rest of a consumer's workflow and read back.
 *
 * It names no resolver, so a Room bound to it opens no verdict thread even when it does
 * name a [verifier]: which seat settles a given Probe's verdict is `resolverFor`, and a
 * consumer that wants verdict threads overrides it — nothing else on the interface is
 * abstract. A verifier is still worth declaring without one, because it is the seat that
 * runs the Probes in the first place.
 *
 * Review edges are not checked here. [reviewsAreAcyclic] is a question about a list of
 * roles, asked by whoever is about to rely on the answer, and a roster is a legitimate
 * value while its edges are still being filled in.
 *
 * @property host The role that hosts the Room. Must be one of [roles].
 * @property roles Every seat, in the order a reader should see them. Ids are distinct.
 * @property verifier The role that runs Probes and posts `Verdict` cards, when the
 *   roster has one. Must be one of [roles]; null — the default — is a roster where the
 *   seats talk and nobody convicts.
 */
@Serializable
data class RosterConfig(
    override val host: RoleId,
    val roles: List<RoleConfig>,
    override val verifier: RoleId? = null,
) : Roster {

    init {
        val duplicated = roles.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "a roster names each role once; duplicated: ${duplicated.joinToString { it.value }}"
        }
        require(roles.any { it.id == host }) { "host '${host.value}' is not one of the roles" }
        require(verifier == null || roles.any { it.id == verifier }) {
            "verifier '${verifier?.value}' is not one of the roles"
        }
    }

    override fun all(): List<RoleConfig> = roles

    companion object {

        /**
         * A [RosterConfig] built from plain ids, for a caller that cannot write a
         * [RoleId] — see [RoleConfig.of] for why Swift is one.
         */
        fun of(
            host: String,
            roles: List<RoleConfig>,
            verifier: String? = null,
        ): RosterConfig = RosterConfig(
            host = RoleId(host),
            roles = roles,
            verifier = verifier?.let { RoleId(it) },
        )
    }
}
