package link.socket.ampere.canon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.link.LinkId

/**
 * A reference to out-of-band content carried by a canon entity — never the
 * bytes themselves.
 *
 * Two shapes exist because the platforms that produce artwork disagree on
 * what an asset *is*: a streaming service hands back a URL template with
 * `{w}`/`{h}` tokens, while a local media library hands back a native image
 * object with no URL at all. Forcing both through `artworkUrl: String?` loses
 * the local-media case outright.
 *
 * Artwork is the motivating case, not the definition. AMPR-258 deferred
 * non-visual use; AMPR-262 resolved it in favour of **reusing this type** for
 * [CanonTable.contentRef] rather than forking a sibling primitive. The two
 * shapes already carry nothing visual in their structure ([Url.width]/
 * [Url.height] are simply unused for a table), and a second ref hierarchy would
 * fork the [link.socket.ampere.plug.spi.AssetResolver] SPI — which exists so
 * consent is enforced once in the contract rather than per implementor. The ref
 * story stays singular; the name is the only vestigial part, and wire names are
 * opaque.
 *
 * Resolving a [CanonAssetRef] to bytes is an
 * [link.socket.ampere.plug.spi.AssetResolver] concern, not a canon concern —
 * this type only names *where* content lives.
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
