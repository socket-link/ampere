package link.socket.ampere.bundle

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip

/**
 * Reads a bundle from a ZIP archive at [zipFile].
 *
 * Lives in jvmMain because [FileSystem.openZip] is JVM-only in okio 3.11.
 * Other targets currently must unpack the archive and use
 * [PluginBundleSource.fromDirectory]; an iOS/Native ZIP reader is a
 * follow-up.
 *
 * The ZIP is opened read-only; the underlying file handle is held for the
 * lifetime of the returned source. Bundles are imported once and discarded,
 * so this is fine in practice — but do not retain the source longer than
 * the import operation.
 */
fun PluginBundleSource.Companion.fromZipFile(
    zipFile: Path,
    fileSystem: FileSystem,
): PluginBundleSource = OkioPluginBundleSource(
    fileSystem = fileSystem.openZip(zipFile),
    root = "/".toPath(),
)
