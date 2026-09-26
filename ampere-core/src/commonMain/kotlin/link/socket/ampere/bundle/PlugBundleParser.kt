package link.socket.ampere.bundle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.AMPERE_RUNTIME_VERSION
import link.socket.ampere.canon.CanonType
import link.socket.ampere.plug.ManifestValidationReason

/**
 * Maximum total byte size accepted by [PlugBundleParser].
 *
 * Bundles are user-importable artefacts; the cap prevents a malicious or
 * malformed archive from exhausting memory before validation begins. The
 * value is intentionally generous — current first-party plugs ship under
 * 1 MiB, but bundled assets (icons, sample data) can grow.
 */
const val MAX_BUNDLE_SIZE_BYTES: Long = 50L * 1024 * 1024

/**
 * Outcome of [PlugBundleParser.parse]. Either a parsed bundle or a typed
 * error describing why parsing failed.
 */
sealed interface BundleParseResult {

    data class Ok(val bundle: PlugBundle) : BundleParseResult

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

    /**
     * `manifest.json` is well-formed JSON but declares something this build
     * cannot honour: a [BundleManifest.minimumAmpereVersion] newer than
     * [AMPERE_RUNTIME_VERSION], or a canon wire name absent from this build's
     * [CanonType].
     *
     * Distinguished from [InvalidManifest] because the manifest is not
     * malformed — the host is too old, or the bundle names a noun that is not
     * canon. Both would otherwise surface as a kotlinx decoder message, which is
     * the SCKT-444 failure shape: a host reporting "malformed manifest" when the
     * truth was "upgrade required" (AMPR-365).
     *
     * Carries [link.socket.ampere.plug.ManifestValidationReason]s rather than a
     * parse-error vocabulary of its own so a host renders one set of manifest
     * diagnostics whether they came from here or from
     * [PlugBundleValidator]; [link.socket.ampere.plug.describe] renders them.
     */
    data class UnsupportedManifest(
        val reasons: List<ManifestValidationReason>,
    ) : BundleParseError
}

/**
 * Parses a [PlugBundleSource] into a [PlugBundle].
 *
 * The parser only enforces structural rules that determine whether the
 * bundle is *readable*: size cap, manifest presence, canon declarations this
 * build can resolve, manifest schema, and format version. Semantic checks
 * (permission well-formedness, id rules, etc.) are the responsibility of
 * [PlugBundleValidator].
 *
 * Canon declarations are a readability question rather than a semantic one
 * because of decode order: [link.socket.ampere.plug.PlugManifest.emits] and its
 * siblings are `Set<CanonType>`, so a name this build does not have fails the
 * enum decode before any validator could describe it. The pre-decode pass in
 * [parse] is what turns that into a typed reason (AMPR-365).
 *
 * @param hostAmpereVersion the runtime version a bundle's
 *   [BundleManifest.minimumAmpereVersion] pin is checked against. Defaults to
 *   this build's [AMPERE_RUNTIME_VERSION].
 */
class PlugBundleParser(
    private val maxBundleSizeBytes: Long = MAX_BUNDLE_SIZE_BYTES,
    private val supportedFormatVersion: Int = CURRENT_BUNDLE_FORMAT_VERSION,
    private val hostAmpereVersion: String = AMPERE_RUNTIME_VERSION,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun parse(source: PlugBundleSource): BundleParseResult {
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

        val manifestText = manifestBytes.decodeToString()

        val unsupported = unsupportedDeclarations(manifestText)
        if (unsupported.isNotEmpty()) {
            return BundleParseResult.Failed(BundleParseError.UnsupportedManifest(unsupported))
        }

        val bundleManifest = try {
            json.decodeFromString(BundleManifest.serializer(), manifestText)
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
            PlugBundle(
                bundleFormatVersion = bundleManifest.bundleFormatVersion,
                manifest = bundleManifest.plug,
                assets = assets,
                signature = signature,
                minimumAmpereVersion = bundleManifest.minimumAmpereVersion,
            ),
        )
    }

    /**
     * Reads the version pin and the canon-naming fields off the raw manifest,
     * before any of it reaches a typed decode.
     *
     * Lenient by construction: every field is read defensively and anything that
     * is not the expected shape is left alone for the decoder to reject as
     * [BundleParseError.InvalidManifest]. This pass answers exactly two
     * questions — is this host new enough, and does it have these nouns — and
     * reports nothing else.
     *
     * A failed pin short-circuits: when the host is too old, the unknown nouns
     * that follow are its symptom, not a second fault, and "upgrade Ampere" is
     * the only diagnostic worth showing.
     */
    private fun unsupportedDeclarations(manifestText: String): List<ManifestValidationReason> {
        val root = try {
            json.parseToJsonElement(manifestText) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return emptyList()

        ampereVersionPinReason(root.string("minimumAmpereVersion"), hostAmpereVersion)
            ?.let { return listOf(it) }

        val plug = root["plug"] as? JsonObject ?: return emptyList()
        val reasons = mutableListOf<ManifestValidationReason>()

        CANON_MANIFEST_FIELDS.forEach { field ->
            reasons += unknownCanonTypes(field = field, element = plug[field])
        }

        (plug["requiredLinks"] as? JsonArray)?.forEachIndexed { index, element ->
            val requirement = element as? JsonObject ?: return@forEachIndexed
            reasons += unknownCanonTypes(
                field = "requiredLinks[$index].minimumScope",
                element = requirement["minimumScope"],
            )
        }

        return reasons
    }

    private fun unknownCanonTypes(
        field: String,
        element: JsonElement?,
    ): List<ManifestValidationReason> {
        val declared = element as? JsonArray ?: return emptyList()
        return declared.mapNotNull { entry ->
            val wireName = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@mapNotNull null
            // CanonType's @SerialName is its wireName for every member, so this
            // resolves exactly what the decode would have accepted.
            if (CanonType.fromWireName(wireName) == null) {
                ManifestValidationReason.UnknownCanonType(field = field, wireName = wireName)
            } else {
                null
            }
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {

        /**
         * The [link.socket.ampere.plug.PlugManifest] fields that name canon
         * types directly. `requiredLinks[].minimumScope` names them too but is
         * nested, so [unsupportedDeclarations] walks it separately;
         * `tableWriteCapabilities` names
         * [link.socket.ampere.canon.table.TableWriteCapability] rather than
         * [CanonType] and is left to the decoder.
         */
        val CANON_MANIFEST_FIELDS = listOf("emits", "consumes", "optionalConsumes")
    }
}
