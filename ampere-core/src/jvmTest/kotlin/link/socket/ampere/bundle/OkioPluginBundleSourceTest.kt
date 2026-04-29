package link.socket.ampere.bundle

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import link.socket.ampere.plugin.PluginManifest
import link.socket.ampere.plugin.permission.PluginPermission
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class OkioPluginBundleSourceTest {

    private val tempRoot = Files.createTempDirectory("plugin-bundle-test")
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }
    private val parser = PluginBundleParser()

    private val fixtureManifest = BundleManifest(
        bundleFormatVersion = 1,
        plugin = PluginManifest(
            id = "github-plugin",
            name = "GitHub Plugin",
            version = "1.0.0",
            requiredPermissions = listOf(
                PluginPermission.NetworkDomain("api.github.com"),
                PluginPermission.MCPServer("mcp://github"),
            ),
        ),
    )
    private val fixtureManifestBytes: ByteArray =
        json.encodeToString(BundleManifest.serializer(), fixtureManifest).encodeToByteArray()
    private val fixtureIcon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val fixtureSignature = byteArrayOf(0x73, 0x69, 0x67)

    @AfterTest
    fun tearDown() {
        Files.walk(tempRoot)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }

    @Test
    fun `fromDirectory parses a bundle laid out on disk`() {
        val bundleDir = tempRoot.resolve("bundle").also { it.createDirectories() }
        bundleDir.resolve(BUNDLE_MANIFEST_PATH).writeBytes(fixtureManifestBytes)
        bundleDir.resolve("assets").createDirectories()
        bundleDir.resolve("assets/icon.png").writeBytes(fixtureIcon)
        bundleDir.resolve(BUNDLE_SIGNATURE_PATH).writeBytes(fixtureSignature)

        val source = PluginBundleSource.fromDirectory(bundleDir.toOkioPath(), FileSystem.SYSTEM)
        val ok = assertIs<BundleParseResult.Ok>(parser.parse(source))

        assertEquals(fixtureManifest.plugin, ok.bundle.manifest)
        assertContentEquals(fixtureIcon, ok.bundle.assets.getValue("assets/icon.png"))
        assertContentEquals(fixtureSignature, ok.bundle.signature)
    }

    @Test
    fun `fromDirectory handles a directory with no assets and no signature`() {
        val bundleDir = tempRoot.resolve("minimal").also { it.createDirectories() }
        bundleDir.resolve(BUNDLE_MANIFEST_PATH).writeBytes(fixtureManifestBytes)

        val source = PluginBundleSource.fromDirectory(bundleDir.toOkioPath(), FileSystem.SYSTEM)
        val ok = assertIs<BundleParseResult.Ok>(parser.parse(source))

        assertEquals(emptyMap(), ok.bundle.assets)
        assertNull(ok.bundle.signature)
    }

    @Test
    fun `fromDirectory on a missing path surfaces MissingManifest via parser`() {
        val ghost = tempRoot.resolve("does-not-exist").toOkioPath()

        val source = PluginBundleSource.fromDirectory(ghost, FileSystem.SYSTEM)
        val failed = assertIs<BundleParseResult.Failed>(parser.parse(source))

        assertEquals(BundleParseError.MissingManifest, failed.error)
    }

    @Test
    fun `fromZipFile parses a bundle stored as a ZIP archive`() {
        val zipFile = tempRoot.resolve("bundle.zip")
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zip ->
            zip.putEntry(BUNDLE_MANIFEST_PATH, fixtureManifestBytes)
            zip.putEntry("assets/icon.png", fixtureIcon)
            zip.putEntry(BUNDLE_SIGNATURE_PATH, fixtureSignature)
        }

        val source = PluginBundleSource.fromZipFile(zipFile.toOkioPath(), FileSystem.SYSTEM)
        val ok = assertIs<BundleParseResult.Ok>(parser.parse(source))

        assertEquals(fixtureManifest.plugin, ok.bundle.manifest)
        assertContentEquals(fixtureIcon, ok.bundle.assets.getValue("assets/icon.png"))
        assertContentEquals(fixtureSignature, ok.bundle.signature)
    }

    @Test
    fun `fromZipFile entries are keyed identically to fromDirectory`() {
        val bundleDir = tempRoot.resolve("dir-bundle").also { it.createDirectories() }
        bundleDir.resolve(BUNDLE_MANIFEST_PATH).writeBytes(fixtureManifestBytes)
        bundleDir.resolve("assets").createDirectories()
        bundleDir.resolve("assets/icon.png").writeBytes(fixtureIcon)

        val zipFile = tempRoot.resolve("zip-bundle.zip")
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zip ->
            zip.putEntry(BUNDLE_MANIFEST_PATH, fixtureManifestBytes)
            zip.putEntry("assets/icon.png", fixtureIcon)
        }

        val dirEntries =
            PluginBundleSource.fromDirectory(bundleDir.toOkioPath(), FileSystem.SYSTEM).entries()
        val zipEntries =
            PluginBundleSource.fromZipFile(zipFile.toOkioPath(), FileSystem.SYSTEM).entries()

        assertEquals(dirEntries, zipEntries)
        assertTrue(BUNDLE_MANIFEST_PATH in dirEntries)
        assertTrue("assets/icon.png" in dirEntries)
    }

    private fun ZipOutputStream.putEntry(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }
}
