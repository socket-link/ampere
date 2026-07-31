package link.socket.ampere.plug.spi

import link.socket.ampere.canon.CanonAssetRef
import link.socket.ampere.link.LinkId

/**
 * Resolves a [CanonAssetRef] to bytes, out of band from Perceive.
 *
 * A third, optional capability alongside [PerceiveSource]/[ExecuteSink] — not
 * a Perceive ride-along, because asset bytes must never enter an Emission or
 * a trace (bloat; replay breakage). Resolution is render-driven, repeatable,
 * and cacheable, none of which match Perceive's PROPEL-driven,
 * fresh-observation lifecycle.
 *
 * Implementations should not enforce consent themselves — wrap with
 * [ConsentEnforcingAssetResolver] so the check happens once, in the SPI
 * contract, not per-implementor.
 */
interface AssetResolver {

    /** Resolve [ref] to bytes at (or near) [spec]'s target dimensions. */
    suspend fun resolve(ref: CanonAssetRef, spec: AssetSpec): Result<AssetBytes>
}

/** Target dimensions a renderer wants; a resolver may return something close but not exact. */
data class AssetSpec(
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
)

/**
 * Resolved asset bytes.
 *
 * @property width Actual width of [bytes], when known — distinct from
 *   [AssetSpec.targetWidth], which is only a request.
 * @property height Actual height of [bytes], when known.
 */
class AssetBytes(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetBytes) return false
        return bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType &&
            width == other.width &&
            height == other.height
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        return result
    }
}

/**
 * Why an [AssetResolver] could not resolve a reference.
 *
 * Distinct from a *transport* failure (network error, decode error), which an
 * implementation reports through its own [Result.failure] before the consent
 * check ever runs — this is closed to failures the SPI layer itself owns.
 */
sealed interface AssetResolutionFailure {

    /** No [link.socket.ampere.link.Link] is registered for this id. */
    data class LinkNotFound(val linkId: LinkId) : AssetResolutionFailure

    /** The Link, its credential, or the Plug's grant on it is revoked. */
    data class ConsentRevoked(val linkId: LinkId) : AssetResolutionFailure
}

/**
 * Carries an [AssetResolutionFailure] through [Result.failure].
 *
 * Resolution never throws on its own; this exception exists so the typed
 * failure survives the `kotlin.Result` boundary, matching
 * [link.socket.ampere.link.LinkResolutionException] and
 * [link.socket.ampere.canon.adapter.CanonConversionException].
 */
class AssetResolutionException(
    val failure: AssetResolutionFailure,
) : Exception("Asset resolution failed: $failure")
