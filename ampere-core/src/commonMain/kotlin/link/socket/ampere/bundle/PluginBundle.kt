package link.socket.ampere.bundle

import kotlinx.serialization.Serializable
import link.socket.ampere.plugin.PluginManifest

/**
 * Bundle format version supported by this build.
 *
 * Bumping this constant signals a structural change to the bundle layout (file
 * names, required entries, on-disk encoding). Field-level additions to
 * [PluginManifest] do not require a bump.
 */
const val CURRENT_BUNDLE_FORMAT_VERSION: Int = 1

/**
 * Path of the manifest file at the root of every bundle.
 */
const val BUNDLE_MANIFEST_PATH: String = "manifest.json"

/**
 * Optional asset directory prefix. Files under this path are surfaced as
 * [PluginBundle.assets] keyed by their path relative to the bundle root.
 */
const val BUNDLE_ASSETS_PREFIX: String = "assets/"

/**
 * Optional detached signature for the manifest. Production verification arrives
 * in a follow-up; today the file is read into [PluginBundle.signature] verbatim
 * so the parser surface matches what marketplace UI imports against.
 */
const val BUNDLE_SIGNATURE_PATH: String = "signature.sig"

/**
 * On-disk representation of `manifest.json`.
 *
 * Wrapping [PluginManifest] keeps the W0.1 plugin contract untouched while
 * letting the bundle format declare its own [bundleFormatVersion]. Older
 * manifests written before the bundle spec lacked this wrapper; the parser
 * does not attempt to read those — bundle import is opt-in for plugins
 * shipping through the marketplace.
 */
@Serializable
data class BundleManifest(
    val bundleFormatVersion: Int,
    val plugin: PluginManifest,
)

/**
 * A successfully parsed plugin bundle.
 *
 * @property bundleFormatVersion the format version declared by the bundle, as
 *   read from `manifest.json`. Validated against [CURRENT_BUNDLE_FORMAT_VERSION]
 *   by the parser before this value is observable.
 * @property manifest the W0.1 [PluginManifest] embedded in the bundle.
 * @property assets file contents from `assets/`, keyed by path relative to the
 *   bundle root (e.g. `assets/icon.png`). Empty when the bundle has no assets.
 * @property signature raw bytes of `signature.sig` if present. Verification is
 *   the responsibility of [PluginBundleSignatureVerifier]; the parser does not
 *   inspect the bytes.
 */
data class PluginBundle(
    val bundleFormatVersion: Int,
    val manifest: PluginManifest,
    val assets: Map<String, ByteArray>,
    val signature: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PluginBundle) return false
        if (bundleFormatVersion != other.bundleFormatVersion) return false
        if (manifest != other.manifest) return false
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
        return result
    }
}
