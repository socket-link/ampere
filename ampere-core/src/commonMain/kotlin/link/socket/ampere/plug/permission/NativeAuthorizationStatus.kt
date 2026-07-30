package link.socket.ampere.plug.permission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The OS's answer to a [PlugPermission.DeviceCapability] authorization
 * request.
 *
 * This models the state EventKit/HealthKit/CoreLocation/PassKit-style
 * authorization APIs expose, which [PlugPermissionGate]'s Ampere-internal
 * [GateResult] does not: a `GateResult.Allow` only proves Ampere *thinks*
 * the wire is fine, not that the OS actually granted the capability.
 *
 * [Restricted] and [EntitlementMissing] are kept distinct from [Denied] on
 * purpose — they have different remedies and must not collapse:
 * - [Denied] is a user decision; re-prompting is legitimate.
 * - [Restricted] is a device policy (MDM, Screen Time, parental controls);
 *   no in-app prompt can change it.
 * - [EntitlementMissing] is a developer-account gap (e.g. FinanceKit case
 *   grants, PassKit pass type ID certificates); prompting the user is
 *   pointless because there is nothing for them to approve.
 *
 * Determining and producing this status is a client-side concern
 * (`NativeAuthorizationGate` in Socket, per-platform). This type only
 * defines the shared vocabulary so a status can cross the wire and be
 * reasoned about upstream.
 */
@Serializable
sealed interface NativeAuthorizationStatus {

    @Serializable
    @SerialName("granted")
    data object Granted : NativeAuthorizationStatus

    @Serializable
    @SerialName("not_determined")
    data object NotDetermined : NativeAuthorizationStatus

    @Serializable
    @SerialName("denied")
    data object Denied : NativeAuthorizationStatus

    @Serializable
    @SerialName("restricted")
    data object Restricted : NativeAuthorizationStatus

    @Serializable
    @SerialName("entitlement_missing")
    data object EntitlementMissing : NativeAuthorizationStatus
}
