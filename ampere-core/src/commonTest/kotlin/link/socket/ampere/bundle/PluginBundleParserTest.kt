package link.socket.ampere.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import link.socket.ampere.plugin.PluginManifest
import link.socket.ampere.plugin.permission.PluginPermission

class PluginBundleParserTest {

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private val parser = PluginBundleParser()

    @Test
    fun `happy path parses manifest assets and signature`() {
        val manifest = BundleManifest(
            bundleFormatVersion = 1,
            plugin = PluginManifest(
                id = "github-plugin",
                name = "GitHub Plugin",
                version = "1.0.0",
                requiredPermissions = listOf(
                    PluginPermission.NetworkDomain("api.github.com"),
                ),
            ),
        )
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to json.encodeToString(BundleManifest.serializer(), manifest)
                    .encodeToByteArray(),
                "assets/icon.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                BUNDLE_SIGNATURE_PATH to byteArrayOf(0x01, 0x02, 0x03),
            ),
        )

        val result = parser.parse(source)

        val ok = assertIs<BundleParseResult.Ok>(result)
        assertEquals(1, ok.bundle.bundleFormatVersion)
        assertEquals(manifest.plugin, ok.bundle.manifest)
        assertEquals(setOf("assets/icon.png"), ok.bundle.assets.keys)
        assertTrue(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47).contentEquals(ok.bundle.assets.getValue("assets/icon.png")),
        )
        assertTrue(byteArrayOf(0x01, 0x02, 0x03).contentEquals(ok.bundle.signature!!))
    }

    @Test
    fun `bundle without optional entries omits assets and signature`() {
        val manifest = BundleManifest(
            bundleFormatVersion = 1,
            plugin = PluginManifest(id = "x", name = "X", version = "0.1.0"),
        )
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to json.encodeToString(BundleManifest.serializer(), manifest)
                    .encodeToByteArray(),
            ),
        )

        val ok = assertIs<BundleParseResult.Ok>(parser.parse(source))

        assertEquals(emptyMap(), ok.bundle.assets)
        assertEquals(null, ok.bundle.signature)
    }

    @Test
    fun `missing manifest returns MissingManifest`() {
        val source = MapBundleSource(
            mapOf("assets/readme.txt" to "hello".encodeToByteArray()),
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        assertEquals(BundleParseError.MissingManifest, failed.error)
    }

    @Test
    fun `invalid manifest schema returns InvalidManifest`() {
        val source = MapBundleSource(
            mapOf(BUNDLE_MANIFEST_PATH to "{ this is not valid json".encodeToByteArray()),
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        assertIs<BundleParseError.InvalidManifest>(failed.error)
    }

    @Test
    fun `manifest missing required fields returns InvalidManifest`() {
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to """{"bundleFormatVersion": 1}""".encodeToByteArray(),
            ),
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        assertIs<BundleParseError.InvalidManifest>(failed.error)
    }

    @Test
    fun `oversized bundle returns BundleTooLarge`() {
        val parserWithSmallLimit = PluginBundleParser(maxBundleSizeBytes = 16)
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to """{"bundleFormatVersion":1,"plugin":{"id":"x","name":"x","version":"1"}}"""
                    .encodeToByteArray(),
                "assets/big.bin" to ByteArray(1024),
            ),
        )

        val failed = assertIs<BundleParseResult.Failed>(parserWithSmallLimit.parse(source))
        val tooLarge = assertIs<BundleParseError.BundleTooLarge>(failed.error)
        assertEquals(16L, tooLarge.limitBytes)
        assertTrue(tooLarge.sizeBytes > 16L)
    }

    @Test
    fun `unknown bundleFormatVersion returns UnknownVersion`() {
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to """
                    {
                      "bundleFormatVersion": 999,
                      "plugin": { "id": "x", "name": "x", "version": "1.0.0" }
                    }
                """.trimIndent().encodeToByteArray(),
            ),
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        val unknown = assertIs<BundleParseError.UnknownVersion>(failed.error)
        assertEquals(999, unknown.declared)
        assertEquals(CURRENT_BUNDLE_FORMAT_VERSION, unknown.supported)
    }

    @Test
    fun `unknown manifest fields are tolerated`() {
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to """
                    {
                      "bundleFormatVersion": 1,
                      "plugin": { "id": "x", "name": "x", "version": "1.0.0" },
                      "futureField": "ignored"
                    }
                """.trimIndent().encodeToByteArray(),
            ),
        )

        assertIs<BundleParseResult.Ok>(parser.parse(source))
    }
}
