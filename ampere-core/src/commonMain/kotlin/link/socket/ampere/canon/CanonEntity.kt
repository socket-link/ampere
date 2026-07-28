package link.socket.ampere.canon

import kotlinx.serialization.Serializable

/**
 * A provenance-carrying instance of a [CanonType].
 *
 * The hierarchy is sealed, so a renderer, adapter registry, or Arc step can
 * `when` over it exhaustively — the same property that makes `AgentSurface`
 * safely renderable per platform. There is deliberately no `Custom(payload)`
 * escape hatch: the escape hatch is [CanonProvenance.nativePayload], which is
 * *attached to a typed entity* rather than replacing one.
 *
 * Ring 1 entities are modelled in full. Ring 2 and Ring 3 entities carry the
 * minimum needed for `PlugManifest` emits/consumes declarations and for
 * provenance to flow; their field sets fill in as each platform integration
 * lands.
 *
 * Every implementation is `@Serializable` with a stable `@SerialName`. These
 * types cross the wire and land in traces — a renamed discriminator breaks
 * `PlaybackRelay` replay of every trace already recorded.
 */
@Serializable
sealed interface CanonEntity {

    val canonId: CanonId

    /** Always the constant matching this type. Never varies per instance. */
    val canonType: CanonType

    val provenance: CanonProvenance

    /** The ring this entity's type belongs to. Convenience over [canonType]. */
    val ring: CanonRing get() = canonType.ring
}
