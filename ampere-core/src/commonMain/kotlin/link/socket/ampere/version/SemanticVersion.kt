package link.socket.ampere.version

/**
 * A parsed `major.minor.patch` version, ordered so two Ampere builds can be compared.
 *
 * Only the numeric core is modelled. A pre-release or build suffix (`0.16.0-SNAPSHOT`,
 * `0.16.0+sha.abc`) is stripped before parsing rather than ordered, because the one question
 * asked of this type — "was the producer a newer build than mine?" — is answered by the
 * release triple. Ordering pre-releases correctly needs the full SemVer §11 rules, and nothing
 * in the repo has a consumer for them; a snapshot of `0.16.0` therefore compares equal to
 * `0.16.0`, which is the conservative answer (no skew claimed within the same release).
 *
 * @see AMPERE_VERSION the current build's version, generated from `gradle.properties`.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        else -> patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Parses [raw] as `major[.minor[.patch]]`, ignoring any `-pre-release` or `+build`
         * suffix. Returns `null` for anything else — an empty string, a non-numeric segment,
         * a negative number, or more than three segments.
         *
         * Null is a first-class answer, not an error to swallow: a caller comparing two
         * versions must treat an unparseable one as "cannot tell", never as "older".
         */
        fun parseOrNull(raw: String): SemanticVersion? {
            val core = raw.trim().substringBefore('-').substringBefore('+')
            if (core.isEmpty()) return null

            val segments = core.split('.')
            if (segments.size > 3) return null

            val numbers = segments.map { segment ->
                segment.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            }

            return SemanticVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }

        /**
         * True when [candidate] parses to a strictly newer version than [reference].
         *
         * False when either side is unparseable — an unknown version is never asserted to be
         * newer, so a caller cannot manufacture a skew out of a malformed string.
         */
        fun isNewer(candidate: String, reference: String): Boolean {
            val parsedCandidate = parseOrNull(candidate) ?: return false
            val parsedReference = parseOrNull(reference) ?: return false
            return parsedCandidate > parsedReference
        }
    }
}
