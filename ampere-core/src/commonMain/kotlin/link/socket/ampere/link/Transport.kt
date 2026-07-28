package link.socket.ampere.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The concrete medium a [Link] runs over.
 *
 * **Transport belongs to the Link, not the Plug.** The Uber Plug does not "have
 * MCP transport"; it declares that it requires a Link of kind [MCP]. Memory
 * requires [FOLDER_MOUNT]; Notify requires [APNS]. That inversion is what lets
 * one authenticated Link serve several Plugs, and what keeps Arc logic ignorant
 * of which wire carried the data.
 *
 * ## Capability is per-platform, not global
 *
 * The tempting model is one enum member per direction — `AppFunctionProvider`,
 * `AppFunctionConsumer`. It is wrong, because the same transport has different
 * powers on different targets:
 *
 *  - [APP_FUNCTION] is bidirectional on Android: Ampere can expose Arcs as
 *    AppFunctions *and* call other apps' functions as Arc steps (the consumer
 *    path is `EXECUTE_APP_FUNCTIONS`-gated and likely restricted at GA).
 *  - iOS has **no consumer equivalent at all.** Cross-app orchestration belongs
 *    to Siri — declare and defer. iOS app-to-app is limited to [URI_SCHEME] and
 *    Shortcuts routes.
 *
 * So capability is a *function of (transport, platform)*, exposed through
 * [capability]. A resolution that ignores it will happily hand an Arc a Link it
 * can never actually drive.
 */
@Serializable
enum class Transport {

    @SerialName("mcp")
    MCP,

    @SerialName("oauth_rest")
    OAUTH_REST,

    @SerialName("native_framework")
    NATIVE_FRAMEWORK,

    @SerialName("uri_scheme")
    URI_SCHEME,

    @SerialName("folder_mount")
    FOLDER_MOUNT,

    @SerialName("apns")
    APNS,

    @SerialName("app_function")
    APP_FUNCTION,

    @SerialName("cli")
    CLI,
    ;

    /**
     * What Ampere may do over this transport on [platform].
     *
     * Unlisted combinations are [TransportCapability.NONE] — the conservative
     * default, so a new platform target cannot silently inherit powers it does
     * not have.
     */
    fun capability(platform: PlatformTarget): TransportCapability =
        CAPABILITIES[this to platform] ?: TransportCapability.NONE

    /**
     * Whether a working transport implementation exists behind this member yet.
     *
     * Deliberately *not* consulted by [LinkResolutionGate]: this ticket ships
     * the interface, and gating resolution on it would make every Link but MCP
     * unresolvable before its transport ticket lands. It exists so tooling and
     * the Plug authoring guide can say which wires are real today.
     */
    val hasImplementation: Boolean
        get() = this == MCP

    companion object {

        private val CAPABILITIES: Map<Pair<Transport, PlatformTarget>, TransportCapability> = buildMap {
            // MCP — Ampere is a client everywhere; hosting a server is only
            // practical off-device.
            putAll(everywhere(MCP, TransportCapability.CONSUME_ONLY))
            put(MCP to PlatformTarget.JVM_DESKTOP, TransportCapability.BIDIRECTIONAL)
            put(MCP to PlatformTarget.MACOS, TransportCapability.BIDIRECTIONAL)

            // OAuth/REST — Ampere calls out; it is never the API provider.
            putAll(everywhere(OAUTH_REST, TransportCapability.CONSUME_ONLY))

            // Native frameworks exist only where the OS does.
            put(NATIVE_FRAMEWORK to PlatformTarget.IOS, TransportCapability.CONSUME_ONLY)
            put(NATIVE_FRAMEWORK to PlatformTarget.ANDROID, TransportCapability.CONSUME_ONLY)
            put(NATIVE_FRAMEWORK to PlatformTarget.MACOS, TransportCapability.CONSUME_ONLY)

            // URI schemes go both ways on mobile: Ampere can open another app
            // and can register a scheme others open.
            put(URI_SCHEME to PlatformTarget.IOS, TransportCapability.BIDIRECTIONAL)
            put(URI_SCHEME to PlatformTarget.ANDROID, TransportCapability.BIDIRECTIONAL)
            put(URI_SCHEME to PlatformTarget.MACOS, TransportCapability.BIDIRECTIONAL)
            put(URI_SCHEME to PlatformTarget.JVM_DESKTOP, TransportCapability.CONSUME_ONLY)

            // Folder mounts: iOS reads through the document picker but cannot
            // publish a mount for other apps to consume.
            put(FOLDER_MOUNT to PlatformTarget.IOS, TransportCapability.CONSUME_ONLY)
            put(FOLDER_MOUNT to PlatformTarget.ANDROID, TransportCapability.BIDIRECTIONAL)
            put(FOLDER_MOUNT to PlatformTarget.JVM_DESKTOP, TransportCapability.BIDIRECTIONAL)
            put(FOLDER_MOUNT to PlatformTarget.MACOS, TransportCapability.BIDIRECTIONAL)

            // APNS is a write-only sink, and *sending* is a server-side act.
            put(APNS to PlatformTarget.JVM_DESKTOP, TransportCapability.CONSUME_ONLY)
            put(APNS to PlatformTarget.MACOS, TransportCapability.CONSUME_ONLY)

            // The headline asymmetry: bidirectional on Android, absent on iOS.
            put(APP_FUNCTION to PlatformTarget.ANDROID, TransportCapability.BIDIRECTIONAL)

            // CLI is a desktop story; macOS post-launch.
            put(CLI to PlatformTarget.JVM_DESKTOP, TransportCapability.CONSUME_ONLY)
            put(CLI to PlatformTarget.MACOS, TransportCapability.CONSUME_ONLY)
        }

        private fun everywhere(
            transport: Transport,
            capability: TransportCapability,
        ): Map<Pair<Transport, PlatformTarget>, TransportCapability> =
            PlatformTarget.entries.associate { (transport to it) to capability }
    }
}

/** A platform Ampere runs on. Capability tables are keyed by this. */
@Serializable
enum class PlatformTarget {
    @SerialName("ios")
    IOS,

    @SerialName("android")
    ANDROID,

    @SerialName("jvm_desktop")
    JVM_DESKTOP,

    @SerialName("macos")
    MACOS,
}

/**
 * Which side of a transport Ampere may act as, on one platform.
 *
 * @property canProvide Ampere may expose its own Arcs through this transport.
 * @property canConsume Ampere may invoke someone else's capability through it.
 */
@Serializable
data class TransportCapability(
    val canProvide: Boolean,
    val canConsume: Boolean,
) {
    fun permits(role: TransportRole): Boolean = when (role) {
        TransportRole.PROVIDER -> canProvide
        TransportRole.CONSUMER -> canConsume
    }

    companion object {
        val NONE = TransportCapability(canProvide = false, canConsume = false)
        val CONSUME_ONLY = TransportCapability(canProvide = false, canConsume = true)
        val PROVIDE_ONLY = TransportCapability(canProvide = true, canConsume = false)
        val BIDIRECTIONAL = TransportCapability(canProvide = true, canConsume = true)
    }
}

/** The side a Plug intends to act as over a required Link. */
@Serializable
enum class TransportRole {
    /** Ampere exposes an Arc through the Link. */
    @SerialName("provider")
    PROVIDER,

    /** Ampere calls out through the Link. The common case. */
    @SerialName("consumer")
    CONSUMER,
}
