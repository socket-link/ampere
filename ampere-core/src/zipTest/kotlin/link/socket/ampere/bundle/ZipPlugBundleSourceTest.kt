package link.socket.ampere.bundle

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.Path

/**
 * `fromZipFile` on every target `zipMain` reaches.
 *
 * Lives in `zipTest` rather than jvmTest so the ZIP path is exercised off the
 * JVM too — the Apple inflater is a different implementation (Kotlin/Native's
 * bundled zlib) from the JVM one, and nothing else in the repo would notice if
 * it stopped working (AMPR-158).
 *
 * The fixture is a committed base64 blob rather than a test resource because
 * okio's `decodeBase64()` and `FileSystem.SYSTEM_TEMPORARY_DIRECTORY` are
 * commonMain symbols with real Native actuals, which sidesteps Kotlin/Native
 * test-resource plumbing entirely. okio has no ZIP *writer* on any platform, so
 * the archive cannot be built in the test; the richer fixtures in
 * `OkioPlugBundleSourceTest` (jvmTest) stay where they are, built by a real
 * `java.util.zip.ZipOutputStream`.
 */
class ZipPlugBundleSourceTest {

    private val fileSystem = FileSystem.SYSTEM
    private val parser = PlugBundleParser()

    /** One directory per test instance, so a failed test cannot poison the next. */
    private val tempRoot: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "plug-bundle-zip-test-${Random.nextInt(0, Int.MAX_VALUE)}"

    init {
        fileSystem.createDirectories(tempRoot)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(tempRoot, mustExist = false)
    }

    @Test
    fun `fromZipFile parses a ZIP bundle end-to-end`() {
        val source = PlugBundleSource.fromZipFile(writeFixtureZip(), fileSystem)

        val ok = assertIs<BundleParseResult.Ok>(parser.parse(source))

        assertEquals(CURRENT_BUNDLE_FORMAT_VERSION, ok.bundle.bundleFormatVersion)
        assertEquals(FIXTURE_PLUG_ID, ok.bundle.manifest.id.value)
        assertEquals(FIXTURE_PLUG_NAME, ok.bundle.manifest.name)
        assertEquals(FIXTURE_PLUG_VERSION, ok.bundle.manifest.version)
        assertContentEquals(FIXTURE_ICON, ok.bundle.assets.getValue(FIXTURE_ICON_PATH))
        assertNull(ok.bundle.signature)
        assertNull(ok.bundle.minimumAmpereVersion)
    }

    @Test
    fun `fromZipFile entries are keyed identically to fromDirectory`() {
        val zipEntries = PlugBundleSource.fromZipFile(writeFixtureZip(), fileSystem).entries()
        val dirEntries = PlugBundleSource.fromDirectory(writeFixtureDirectory(), fileSystem).entries()

        assertEquals(dirEntries, zipEntries)
        assertTrue(BUNDLE_MANIFEST_PATH in zipEntries)
        assertTrue(FIXTURE_ICON_PATH in zipEntries)
    }

    @Test
    fun `fromZipFile totalSize sums uncompressed entry sizes`() {
        // The parser's oversized-bundle guard runs before anything is inflated, which only
        // defends against a deflate bomb while a ZIP entry's reported size is its
        // *uncompressed* one from the central directory. Both fixture entries are deflated,
        // so a compressed-size reading would come out smaller than this.
        val expected = (FIXTURE_MANIFEST_JSON.encodeToByteArray().size + FIXTURE_ICON.size).toLong()

        val zipSize = PlugBundleSource.fromZipFile(writeFixtureZip(), fileSystem).totalSize()

        assertEquals(expected, zipSize)
        assertEquals(
            PlugBundleSource.fromDirectory(writeFixtureDirectory(), fileSystem).totalSize(),
            zipSize,
        )
    }

    @Test
    fun `the committed ZIP fixture holds the manifest this test asserts against`() {
        // Ties the opaque blob to the readable constant: the two are generated together (see
        // FIXTURE_ZIP_BASE64) and nothing else would catch them drifting apart.
        val source = PlugBundleSource.fromZipFile(writeFixtureZip(), fileSystem)

        assertEquals(
            FIXTURE_MANIFEST_JSON,
            source.readEntry(BUNDLE_MANIFEST_PATH)?.decodeToString(),
        )
    }

    private fun writeFixtureZip(): Path {
        val zipBytes = requireNotNull(FIXTURE_ZIP_BASE64.decodeBase64()) {
            "FIXTURE_ZIP_BASE64 is not valid base64"
        }
        val zipFile = tempRoot / "bundle.zip"
        fileSystem.write(zipFile) { write(zipBytes) }
        return zipFile
    }

    /** The same two entries as the ZIP fixture, laid out as a directory tree. */
    private fun writeFixtureDirectory(): Path {
        val bundleDir = tempRoot / "bundle"
        val iconFile = bundleDir / FIXTURE_ICON_PATH
        fileSystem.createDirectories(iconFile.parent ?: bundleDir)
        fileSystem.write(bundleDir / BUNDLE_MANIFEST_PATH) {
            write(FIXTURE_MANIFEST_JSON.encodeToByteArray())
        }
        fileSystem.write(iconFile) { write(FIXTURE_ICON) }
        return bundleDir
    }

    private companion object {

        const val FIXTURE_PLUG_ID = "zip-fixture-plug"
        const val FIXTURE_PLUG_NAME = "ZIP Fixture Plug"
        const val FIXTURE_PLUG_VERSION = "1.0.0"
        const val FIXTURE_ICON_PATH = BUNDLE_ASSETS_PREFIX + "icon.png"

        /** Byte-for-byte the `manifest.json` entry inside [FIXTURE_ZIP_BASE64]. */
        const val FIXTURE_MANIFEST_JSON =
            """{"bundleFormatVersion":1,"plug":{"id":"zip-fixture-plug",""" +
                """"name":"ZIP Fixture Plug","version":"1.0.0"}}"""

        /** Byte-for-byte the `assets/icon.png` entry: an 8-byte PNG signature. */
        val FIXTURE_ICON = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /**
         * A 325-byte ZIP holding exactly two deflated entries — `manifest.json`
         * ([FIXTURE_MANIFEST_JSON], 102 bytes uncompressed) and `assets/icon.png`
         * ([FIXTURE_ICON], 8 bytes uncompressed) — with no directory entries, matching what
         * a real archiver emits. Both entries are deflated rather than stored so the test
         * exercises each platform's inflater.
         *
         * Regenerate with, from the repository root:
         *
         * ```
         * python3 - <<'EOF'
         * import base64, io, zipfile
         * MANIFEST = (b'{"bundleFormatVersion":1,"plug":{"id":"zip-fixture-plug",'
         *             b'"name":"ZIP Fixture Plug","version":"1.0.0"}}')
         * ICON = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
         * buf = io.BytesIO()
         * with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
         *     for name, data in (("manifest.json", MANIFEST), ("assets/icon.png", ICON)):
         *         info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
         *         info.compress_type = zipfile.ZIP_DEFLATED
         *         info.external_attr = 0o644 << 16
         *         info.create_system = 3
         *         z.writestr(info, data)
         * print(base64.b64encode(buf.getvalue()).decode())
         * EOF
         * ```
         *
         * The fixed 1980-01-01 entry timestamps keep the blob byte-stable across
         * regenerations. Nothing here reads them: a ZIP entry's
         * `lastModifiedAtMillis()` is UTC rather than host-local on Kotlin/Native
         * (okio 3.9.0 release note), so an assertion on one would pass on the JVM and
         * fail on iOS.
         */
        const val FIXTURE_ZIP_BASE64 =
            "UEsDBBQAAAAIAAAAIQDi3tk0VQAAAGYAAAANAAAAbWFuaWZlc3QuanNvbqtWSirNS8lJdcsvyk0s" +
                "CUstKs7Mz1OyMtRRKsgpTVeyqlbKTFGyUqrKLNBNy6woKS1K1QVL6CjlJeamAmWiPAMU3CAyCgEQ" +
                "mTKYKUqGegZ6Bkq1tQBQSwMEFAAAAAgAAAAhAKQJB3oKAAAACAAAAA8AAABhc3NldHMvaWNvbi5w" +
                "bmfrDPBz5+WS4gIAUEsBAhQDFAAAAAgAAAAhAOLe2TRVAAAAZgAAAA0AAAAAAAAAAAAAAKQBAAAA" +
                "AG1hbmlmZXN0Lmpzb25QSwECFAMUAAAACAAAACEApAkHegoAAAAIAAAADwAAAAAAAAAAAAAApAGA" +
                "AAAAYXNzZXRzL2ljb24ucG5nUEsFBgAAAAACAAIAeAAAALcAAAAAAA=="
    }
}
