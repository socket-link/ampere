package link.socket.ampere.canon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.link.LinkId

/**
 * A reference to visual media carried by a canon entity — never the bytes
 * themselves.
 *
 * Two shapes exist because the platforms that produce artwork disagree on
 * what an asset *is*: a streaming service hands back a URL template with
 * `{w}`/`{h}` tokens, while a local media library hands back a native image
 * object with no URL at all. Forcing both through `artworkUrl: String?` loses
 * the local-media case outright.
 *
 * Resolving a [CanonAssetRef] to bytes is an
 * [link.socket.ampere.plug.spi.AssetResolver] concern, not a canon concern —
 * this type only names *where* an asset lives.
 */
@Serializable
sealed interface CanonAssetRef {

    /**
     * A URL, possibly a template.
     *
     * @property template May contain `{w}`/`{h}` tokens for a resolver to
     *   fill at the requested [link.socket.ampere.plug.spi.AssetSpec]
     *   dimensions. A template with no tokens is just a URL.
     * @property width Known width, when the template's own dimensions are
     *   fixed rather than requested.
     * @property height Known height, when the template's own dimensions are
     *   fixed rather than requested.
     */
    @Serializable
    @SerialName("canon_asset_ref.url")
    data class Url(
        val template: String,
        val width: Int? = null,
        val height: Int? = null,
    ) : CanonAssetRef

    /**
     * A handle into a native object a [link.socket.ampere.plug.spi.AssetResolver]
     * must decode.
     *
     * @property linkId The Link that can resolve it — also the consent key:
     *   resolution is permitted iff the per-(Plug, Link) grant that produced
     *   the entity is still valid.
     * @property nativeId Opaque; never parsed by consumers, mirroring
     *   [SourceHandle.nativeId].
     */
    @Serializable
    @SerialName("canon_asset_ref.native_handle")
    data class NativeHandle(
        val linkId: LinkId,
        val nativeId: String,
    ) : CanonAssetRef
}
