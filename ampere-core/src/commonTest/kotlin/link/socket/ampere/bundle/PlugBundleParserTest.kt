package link.socket.ampere.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import link.socket.ampere.AMPERE_RUNTIME_VERSION
import link.socket.ampere.canon.CanonType
import link.socket.ampere.plug.ManifestValidationReason
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission

class PlugBundleParserTest {

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private val parser = PlugBundleParser()

    private fun manifestSource(manifestJson: String): MapBundleSource = MapBundleSource(
        mapOf(BUNDLE_MANIFEST_PATH to manifestJson.trimIndent().encodeToByteArray()),
    )

    @Test
    fun `happy path parses manifest assets and signature`() {
        val manifest = BundleManifest(
            bundleFormatVersion = 1,
            plug = PlugManifest(
                id = PlugId("github-plug"),
                name = "GitHub Plug",
                version = "1.0.0",
                requiredPermissions = listOf(
                    PlugPermission.NetworkDomain("api.github.com"),
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
        assertEquals(manifest.plug, ok.bundle.manifest)
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
            plug = PlugManifest(id = PlugId("x"), name = "X", version = "0.1.0"),
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
        val parserWithSmallLimit = PlugBundleParser(maxBundleSizeBytes = 16)
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to """{"bundleFormatVersion":1,"plug":{"id":"x","name":"x","version":"1"}}"""
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
                      "plug": { "id": "x", "name": "x", "version": "1.0.0" }
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
    fun `a bundle without a version pin parses with a null pin`() {
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "plug": { "id": "x", "name": "x", "version": "1.0.0" }
            }
            """,
        )

        val ok = assertIs<BundleParseResult.Ok>(parser.parse(source))
        assertEquals(null, ok.bundle.minimumAmpereVersion)
    }

    @Test
    fun `a bundle pinned at or below the host parses and surfaces the pin`() {
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "minimumAmpereVersion": "0.12.0",
              "plug": { "id": "x", "name": "x", "version": "1.0.0", "emits": ["table"] }
            }
            """,
        )

        val ok = assertIs<BundleParseResult.Ok>(PlugBundleParser(hostAmpereVersion = "0.15.0").parse(source))
        assertEquals("0.12.0", ok.bundle.minimumAmpereVersion)
    }

    @Test
    fun `a bundle pinned to a newer Ampere reports the version rather than a decode failure`() {
        // SCKT-444's shape: a host on 0.11.0 meeting a bundle that names `table`
        // — admitted in 0.12.0 — used to be told its manifest was malformed.
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "minimumAmpereVersion": "0.12.0",
              "plug": { "id": "x", "name": "x", "version": "1.0.0", "emits": ["table"] }
            }
            """,
        )

        val failed = assertIs<BundleParseResult.Failed>(
            PlugBundleParser(hostAmpereVersion = "0.11.0").parse(source),
        )
        val unsupported = assertIs<BundleParseError.UnsupportedManifest>(failed.error)
        assertEquals(
            listOf(
                ManifestValidationReason.AmpereVersionTooOld(
                    required = "0.12.0",
                    current = "0.11.0",
                ),
            ),
            unsupported.reasons,
        )
    }

    @Test
    fun `a too-old host is told to upgrade and not about the nouns it lacks`() {
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "minimumAmpereVersion": "9.0.0",
              "plug": {
                "id": "x", "name": "x", "version": "1.0.0",
                "emits": ["noun_from_the_future"]
              }
            }
            """,
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        val unsupported = assertIs<BundleParseError.UnsupportedManifest>(failed.error)
        assertEquals(1, unsupported.reasons.size)
        assertIs<ManifestValidationReason.AmpereVersionTooOld>(unsupported.reasons.single())
    }

    @Test
    fun `a malformed version pin is reported rather than ignored`() {
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "minimumAmpereVersion": "latest",
              "plug": { "id": "x", "name": "x", "version": "1.0.0" }
            }
            """,
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        val unsupported = assertIs<BundleParseError.UnsupportedManifest>(failed.error)
        assertEquals(
            listOf(ManifestValidationReason.MalformedAmpereVersion("latest")),
            unsupported.reasons,
        )
    }

    @Test
    fun `an extension type in emits is reported by field and wire name`() {
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "plug": {
                "id": "x", "name": "x", "version": "1.0.0",
                "emits": ["com.acme.invoice"]
              }
            }
            """,
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        val unsupported = assertIs<BundleParseError.UnsupportedManifest>(failed.error)
        assertEquals(
            listOf(
                ManifestValidationReason.UnknownCanonType(
                    field = "emits",
                    wireName = "com.acme.invoice",
                ),
            ),
            unsupported.reasons,
        )
    }

    @Test
    fun `every canon-naming field is checked and all unknown names are reported at once`() {
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "plug": {
                "id": "x", "name": "x", "version": "1.0.0",
                "emits": ["com.acme.invoice"],
                "consumes": ["persons"],
                "optionalConsumes": ["Photo"],
                "requiredLinks": [
                  {
                    "name": "calendar",
                    "transport": "mcp",
                    "direction": "read",
                    "minimumScope": ["calendar_events"]
                  }
                ]
              }
            }
            """,
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        val unsupported = assertIs<BundleParseError.UnsupportedManifest>(failed.error)
        assertEquals(
            listOf(
                ManifestValidationReason.UnknownCanonType("emits", "com.acme.invoice"),
                ManifestValidationReason.UnknownCanonType("consumes", "persons"),
                ManifestValidationReason.UnknownCanonType("optionalConsumes", "Photo"),
                ManifestValidationReason.UnknownCanonType("requiredLinks[0].minimumScope", "calendar_events"),
            ),
            unsupported.reasons,
        )
    }

    @Test
    fun `a manifest naming only canon this build has parses`() {
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "minimumAmpereVersion": "$AMPERE_RUNTIME_VERSION",
              "plug": {
                "id": "x", "name": "x", "version": "1.0.0",
                "emits": ["table"],
                "consumes": ["person"],
                "optionalConsumes": ["photo"],
                "requiredLinks": [
                  {
                    "name": "sheets",
                    "transport": "mcp",
                    "direction": "read",
                    "minimumScope": ["table"]
                  }
                ]
              }
            }
            """,
        )

        val ok = assertIs<BundleParseResult.Ok>(parser.parse(source))
        assertEquals(setOf(CanonType.TABLE), ok.bundle.manifest.emits)
        assertEquals(AMPERE_RUNTIME_VERSION, ok.bundle.minimumAmpereVersion)
    }

    @Test
    fun `a canon field of the wrong JSON shape is left to the decoder`() {
        // The pre-pass answers two questions and reports nothing else; a
        // structurally wrong manifest is still InvalidManifest.
        val source = manifestSource(
            """
            {
              "bundleFormatVersion": 1,
              "plug": { "id": "x", "name": "x", "version": "1.0.0", "emits": "person" }
            }
            """,
        )

        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))
        assertIs<BundleParseError.InvalidManifest>(failed.error)
    }

    @Test
    fun `unknown manifest fields are tolerated`() {
        val source = MapBundleSource(
            mapOf(
                BUNDLE_MANIFEST_PATH to """
                    {
                      "bundleFormatVersion": 1,
                      "plug": { "id": "x", "name": "x", "version": "1.0.0" },
                      "futureField": "ignored"
                    }
                """.trimIndent().encodeToByteArray(),
            ),
        )

        assertIs<BundleParseResult.Ok>(parser.parse(source))
    }
}
