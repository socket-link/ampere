package link.socket.ampere.bundle

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip

/**
 * Reads a bundle from a ZIP archive at [zipFile].
 *
 * Lives in `zipMain` — the JVM/Android/Apple intersection — rather than in
 * commonMain because [FileSystem.openZip] comes from okio's `zlibMain`, which
 * every target here reaches but Kotlin/JS and Wasm do not. Nothing about the
 * body is platform-specific; on Apple targets the inflater binds Kotlin/Native's
 * bundled zlib (AMPR-158).
 *
 * The ZIP is opened read-only; the underlying file handle is held for the
 * lifetime of the returned source. Bundles are imported once and discarded,
 * so this is fine in practice — but do not retain the source longer than
 * the import operation.
 *
 * Reads only `isRegularFile` and `size` off each entry, deliberately: a ZIP
 * entry's `lastModifiedAtMillis()` is read as UTC rather than host-local on
 * Kotlin/Native (okio 3.9.0 release note), so bundle parsing must not start
 * depending on entry timestamps without rechecking that upstream.
 */
fun PlugBundleSource.Companion.fromZipFile(
    zipFile: Path,
    fileSystem: FileSystem,
): PlugBundleSource = OkioPlugBundleSource(
    fileSystem = fileSystem.openZip(zipFile),
    root = "/".toPath(),
)
