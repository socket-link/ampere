package link.socket.ampere.bundle

/**
 * Read-only view over the entries of a plugin bundle.
 *
 * The parser is intentionally agnostic about how a bundle is laid out on disk
 * — ZIP archives, on-disk directories, and in-memory test fixtures are all
 * exposed through this interface. Concrete platform-specific implementations
 * (e.g. ZIP-on-JVM) live alongside their platform's source set; commonMain
 * ships only [MapBundleSource] for tests and trusted in-process callers.
 */
interface PluginBundleSource {

    /**
     * Lists every entry path in the bundle, relative to the bundle root.
     *
     * Paths use forward slashes regardless of host platform. Implementations
     * must not include directory entries — only files that [readEntry] can
     * resolve. The returned collection is not required to be sorted.
     */
    fun entries(): Set<String>

    /**
     * Returns the bytes for the entry at [path], or `null` if absent.
     *
     * Path matching is case-sensitive. Calls are expected to be idempotent;
     * repeated reads of the same entry must return equal byte content.
     */
    fun readEntry(path: String): ByteArray?

    /**
     * Total byte size of the bundle, summed across every entry.
     *
     * Used by the parser to enforce the oversized-bundle guard before any
     * entry is decoded. Implementations may compute this lazily but must
     * return a stable value for a given source.
     */
    fun totalSize(): Long

    companion object
}

/**
 * In-memory [PluginBundleSource] backed by a map of entry paths to bytes.
 *
 * Suitable for tests and for callers that have already materialised a bundle
 * (e.g. a freshly downloaded archive that has been unpacked into memory).
 */
class MapBundleSource(
    private val entries: Map<String, ByteArray>,
) : PluginBundleSource {

    override fun entries(): Set<String> = entries.keys

    override fun readEntry(path: String): ByteArray? = entries[path]

    override fun totalSize(): Long = entries.values.sumOf { it.size.toLong() }
}
