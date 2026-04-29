package link.socket.ampere.bundle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Maximum total byte size accepted by [PluginBundleParser].
 *
 * Bundles are user-importable artefacts; the cap prevents a malicious or
 * malformed archive from exhausting memory before validation begins. The
 * value is intentionally generous — current first-party plugins ship under
 * 1 MiB, but bundled assets (icons, sample data) can grow.
 */
const val MAX_BUNDLE_SIZE_BYTES: Long = 50L * 1024 * 1024

/**
 * Outcome of [PluginBundleParser.parse]. Either a parsed bundle or a typed
 * error describing why parsing failed.
 */
sealed interface BundleParseResult {

    data class Ok(val bundle: PluginBundle) : BundleParseResult

    data class Failed(val error: BundleParseError) : BundleParseResult
}

/**
 * Reasons a bundle may fail to parse. The list is closed; callers can rely on
 * `when` being exhaustive.
 */
sealed interface BundleParseError {

    /** The bundle has no `manifest.json` entry. */
    data object MissingManifest : BundleParseError

    /** `manifest.json` exists but is not valid JSON or does not match the schema. */
    data class InvalidManifest(val message: String) : BundleParseError

    /**
     * Total entry size exceeds [MAX_BUNDLE_SIZE_BYTES].
     *
     * Reported with the actual size so callers (e.g. marketplace UI) can show
     * the user how far over the limit a bundle ran.
     */
    data class BundleTooLarge(val sizeBytes: Long, val limitBytes: Long) : BundleParseError

    /**
     * The manifest declares a [bundleFormatVersion] this build does not
     * understand. Distinguished from [InvalidManifest] so newer bundles
     * surface a clear "upgrade required" message rather than looking
     * malformed.
     */
    data class UnknownVersion(val declared: Int, val supported: Int) : BundleParseError
}

/**
 * Parses a [PluginBundleSource] into a [PluginBundle].
 *
 * The parser only enforces structural rules that determine whether the
 * bundle is *readable*: size cap, manifest presence, manifest schema, and
 * format version. Semantic checks (permission well-formedness, id rules,
 * etc.) are the responsibility of [PluginBundleValidator].
 */
class PluginBundleParser(
    private val maxBundleSizeBytes: Long = MAX_BUNDLE_SIZE_BYTES,
    private val supportedFormatVersion: Int = CURRENT_BUNDLE_FORMAT_VERSION,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun parse(source: PluginBundleSource): BundleParseResult {
        val totalSize = source.totalSize()
        if (totalSize > maxBundleSizeBytes) {
            return BundleParseResult.Failed(
                BundleParseError.BundleTooLarge(
                    sizeBytes = totalSize,
                    limitBytes = maxBundleSizeBytes,
                ),
            )
        }

        val manifestBytes = source.readEntry(BUNDLE_MANIFEST_PATH)
            ?: return BundleParseResult.Failed(BundleParseError.MissingManifest)

        val bundleManifest = try {
            json.decodeFromString(BundleManifest.serializer(), manifestBytes.decodeToString())
        } catch (e: SerializationException) {
            return BundleParseResult.Failed(
                BundleParseError.InvalidManifest(e.message ?: "manifest.json failed to decode"),
            )
        } catch (e: IllegalArgumentException) {
            return BundleParseResult.Failed(
                BundleParseError.InvalidManifest(e.message ?: "manifest.json failed to decode"),
            )
        }

        if (bundleManifest.bundleFormatVersion != supportedFormatVersion) {
            return BundleParseResult.Failed(
                BundleParseError.UnknownVersion(
                    declared = bundleManifest.bundleFormatVersion,
                    supported = supportedFormatVersion,
                ),
            )
        }

        val assets = source.entries()
            .asSequence()
            .filter { it.startsWith(BUNDLE_ASSETS_PREFIX) && it != BUNDLE_ASSETS_PREFIX }
            .mapNotNull { path -> source.readEntry(path)?.let { path to it } }
            .toMap()

        val signature = source.readEntry(BUNDLE_SIGNATURE_PATH)

        return BundleParseResult.Ok(
            PluginBundle(
                bundleFormatVersion = bundleManifest.bundleFormatVersion,
                manifest = bundleManifest.plugin,
                assets = assets,
                signature = signature,
            ),
        )
    }
}
