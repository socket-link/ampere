package link.socket.ampere.bundle

import kotlinx.serialization.Serializable
import link.socket.ampere.plug.PlugManifest

/**
 * Bundle format version supported by this build.
 *
 * Bumping this constant signals a structural change to the bundle layout (file
 * names, required entries, on-disk encoding). Field-level additions to
 * [PlugManifest] do not require a bump.
 */
const val CURRENT_BUNDLE_FORMAT_VERSION: Int = 1

/**
 * Path of the manifest file at the root of every bundle.
 */
const val BUNDLE_MANIFEST_PATH: String = "manifest.json"

/**
 * Optional asset directory prefix. Files under this path are surfaced as
 * [PlugBundle.assets] keyed by their path relative to the bundle root.
 */
const val BUNDLE_ASSETS_PREFIX: String = "assets/"

/**
 * Optional detached signature for the manifest. Production verification arrives
 * in a follow-up; today the file is read into [PlugBundle.signature] verbatim
 * so the parser surface matches what marketplace UI imports against.
 */
const val BUNDLE_SIGNATURE_PATH: String = "signature.sig"

/**
 * On-disk representation of `manifest.json`.
 *
 * Wrapping [PlugManifest] keeps the W0.1 plug contract untouched while
 * letting the bundle format declare its own [bundleFormatVersion]. Older
 * manifests written before the bundle spec lacked this wrapper; the parser
 * does not attempt to read those — bundle import is opt-in for plugs
 * shipping through the marketplace.
 *
 * @property minimumAmpereVersion the oldest Ampere release whose
 *   [link.socket.ampere.canon.CanonType] vocabulary covers every canon name in
 *   [plug] — the marketplace rule's pin (`docs/concepts/domain-canon.md`). A
 *   host older than this cannot read the bundle's canon declarations, and says
 *   so as [BundleParseError.UnsupportedManifest] carrying
 *   [link.socket.ampere.plug.ManifestValidationReason.AmpereVersionTooOld]
 *   instead of letting the enum decode fail as a malformed manifest. `null` —
 *   the default, and what every bundle written before AMPR-365 decodes to —
 *   means the bundle makes no claim; its canon names are still checked against
 *   this build's vocabulary, the host just cannot tell "too old" from "typo"
 *   apart when one of them is unknown. Lives on the wrapper rather than on
 *   [PlugManifest] because it is a property of the *distribution*: an in-repo
 *   plug compiles against the canon it ships with, so it has nothing to pin.
 *   Additive with a default, so it does not bump
 *   [CURRENT_BUNDLE_FORMAT_VERSION].
 */
@Serializable
data class BundleManifest(
    val bundleFormatVersion: Int,
    val plug: PlugManifest,
    val minimumAmpereVersion: String? = null,
)

/**
 * A successfully parsed plug bundle.
 *
 * @property bundleFormatVersion the format version declared by the bundle, as
 *   read from `manifest.json`. Validated against [CURRENT_BUNDLE_FORMAT_VERSION]
 *   by the parser before this value is observable.
 * @property manifest the W0.1 [PlugManifest] embedded in the bundle.
 * @property assets file contents from `assets/`, keyed by path relative to the
 *   bundle root (e.g. `assets/icon.png`). Empty when the bundle has no assets.
 * @property signature raw bytes of `signature.sig` if present. Verification is
 *   the responsibility of [PlugBundleSignatureVerifier]; the parser does not
 *   inspect the bytes.
 * @property minimumAmpereVersion the bundle's canon pin, as read from
 *   `manifest.json` — see [BundleManifest.minimumAmpereVersion]. Carried onto
 *   the parsed bundle so [PlugBundleValidator] can re-check it against the host
 *   runtime version for a bundle it was handed rather than parsed.
 */
data class PlugBundle(
    val bundleFormatVersion: Int,
    val manifest: PlugManifest,
    val assets: Map<String, ByteArray>,
    val signature: ByteArray?,
    val minimumAmpereVersion: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlugBundle) return false
        if (bundleFormatVersion != other.bundleFormatVersion) return false
        if (manifest != other.manifest) return false
        if (minimumAmpereVersion != other.minimumAmpereVersion) return false
        if (assets.keys != other.assets.keys) return false
        for ((path, bytes) in assets) {
            if (!bytes.contentEquals(other.assets[path])) return false
        }
        if (signature == null) {
            if (other.signature != null) return false
        } else {
            if (other.signature == null) return false
            if (!signature.contentEquals(other.signature)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = bundleFormatVersion
        result = 31 * result + manifest.hashCode()
        for ((path, bytes) in assets) {
            result = 31 * result + path.hashCode()
            result = 31 * result + bytes.contentHashCode()
        }
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + (minimumAmpereVersion?.hashCode() ?: 0)
        return result
    }
}
