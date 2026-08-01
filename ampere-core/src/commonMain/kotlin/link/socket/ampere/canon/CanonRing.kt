package link.socket.ampere.canon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a canon type's provenance comes from.
 *
 * A ring is a statement about *binding provenance*, not about support level. A
 * [PLATFORM] type is every bit as usable by an Arc as an [INTERCHANGE] one; what
 * the ring records is whether the type can travel through the operating system's
 * own cross-app registry, or whether Ampere is the only thing that knows it is a
 * canonical noun.
 *
 * Ring membership was settled by the SDK pass documented in
 * `docs/ampr-222-domain-type-canon-v1.md`. These definitions are
 * intentionally vendor-neutral — which platform ships which schema, and the
 * per-type projection detail, lives in the binding-table rows owned by the
 * edge modules (`ampere-bindings-apple`, `ampere-bindings-android`), not here.
 */
@Serializable
enum class CanonRing {

    /**
     * Has an OS-canonical interchange schema on at least one platform, so the
     * entity can cross app boundaries through that platform's own registry
     * without lossy contortion.
     */
    @SerialName("interchange")
    INTERCHANGE,

    /**
     * Reaches Ampere via a native framework rather than an OS-canonical
     * cross-app schema — inside the OS, but outside its interchange
     * vocabulary.
     */
    @SerialName("platform")
    PLATFORM,

    /**
     * Arrives only over a service Link — a non-OS transport such as an MCP
     * server, an OAuth REST API, a folder mount, or a CLI. Never reachable
     * through a platform-native registry or framework.
     */
    @SerialName("service")
    SERVICE,
}
