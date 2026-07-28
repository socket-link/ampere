package link.socket.ampere.bundle

import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * [PlugBundleSource] backed by an okio [FileSystem].
 *
 * Used by [PlugBundleSource.fromDirectory] (commonMain) and
 * `PlugBundleSource.fromZipFile` (jvmMain — okio's `openZip` is JVM-only
 * in this version).
 *
 * Entry keys are normalised to forward-slash paths relative to the bundle
 * root, so a bundle imported as a directory and the same bundle imported as a
 * ZIP produce identical [PlugBundleSource.entries] sets.
 */
internal class OkioPlugBundleSource(
    private val fileSystem: FileSystem,
    private val root: Path,
) : PlugBundleSource {

    private val index: Map<String, Path> by lazy { buildIndex() }

    override fun entries(): Set<String> = index.keys

    override fun readEntry(path: String): ByteArray? {
        val resolved = index[path] ?: return null
        return try {
            fileSystem.read(resolved) { readByteArray() }
        } catch (e: IOException) {
            null
        }
    }

    override fun totalSize(): Long =
        index.values.sumOf { fileSystem.metadataOrNull(it)?.size ?: 0L }

    private fun buildIndex(): Map<String, Path> {
        val rootMeta = fileSystem.metadataOrNull(root) ?: return emptyMap()
        if (!rootMeta.isDirectory) return emptyMap()

        val entries = mutableMapOf<String, Path>()
        for (entry in fileSystem.listRecursively(root)) {
            val meta = fileSystem.metadataOrNull(entry) ?: continue
            if (!meta.isRegularFile) continue
            val key = entry.relativeTo(root).toString().replace('\\', '/')
            if (key.isNotEmpty()) entries[key] = entry
        }
        return entries
    }
}

/**
 * Reads a bundle from an unpacked directory at [directory].
 *
 * If [directory] does not exist or is not a directory, the returned source
 * is empty and the parser will surface [BundleParseError.MissingManifest].
 */
fun PlugBundleSource.Companion.fromDirectory(
    directory: Path,
    fileSystem: FileSystem,
): PlugBundleSource = OkioPlugBundleSource(fileSystem, directory)
