package link.socket.ampere.agents.domain.cognition

import kotlinx.serialization.Serializable

/**
 * Defines file access constraints for an agent's cognitive context.
 *
 * FileAccessScope uses glob patterns to express what files an agent can read and write.
 * When multiple Sparks specify access patterns, they're combined using:
 * - Intersection for read/write patterns (can only access what ALL sparks allow)
 * - Union for forbidden patterns (blocked by ANY means blocked)
 *
 * Standard glob syntax is used, matched segment by segment against a
 * workspace-relative path by [matches]:
 * - `*` matches any run of characters except the path separator
 * - `**` as a whole segment matches any number of segments, *including none*
 * - `?` matches any single character except the path separator
 * - `[abc]` matches any character in the set; `[a-z]` a range, `[!abc]` a negation
 *
 * Examples, with the separators spaced out — a literal `*` adjacent to a `/`
 * either opens a nested comment or closes this one:
 * - `src/ ** / *.kt` — all Kotlin files under `src`
 * - `*.md` — all Markdown files in the root
 * - `** / .env` — all env files anywhere, the root included
 *
 * ### Enforcement (AMPR-414)
 *
 * [allowsRead] / [allowsWrite] are the gate, and the file-touching tools
 * (`read_code_file`, `write_code_file`) consult them before touching the
 * filesystem. The scope reaches them on
 * [ExecutionRequest.fileAccessScope][link.socket.ampere.agents.execution.request.ExecutionRequest.fileAccessScope],
 * stamped by the agent that dispatches the plan step. This bounds *which paths
 * within* a workspace may be touched; `ExecutionWorkspace` (AMPR-300) bounds
 * *where* the workspace is. The two compose; neither replaces the other.
 *
 * ### An empty pattern set denies (AMPR-414)
 *
 * `emptySet()` means "no access", not "no opinion" — that is what makes
 * [NoAccess] and [ReadOnly] mean what they say, and what keeps composition
 * monotone (intersection can only shrink a set, so an empty side collapses the
 * result). A Spark that wants to contribute *no* constraint on an axis says so
 * by widening, not by emptying: [AnyPath] on that axis, or
 * `fileAccessScope = null` to contribute no constraint at all. This is why
 * [link.socket.ampere.agents.domain.cognition.sparks.ProjectSpark] declares
 * `writePatterns = setOf(AnyPath)` rather than `emptySet()`: its intent is
 * "role sparks decide writes", and under set intersection an empty set says
 * "nobody writes anything, ever".
 */
@Serializable
data class FileAccessScope(
    /**
     * Glob patterns for files the agent can read.
     * Empty set means no read access — see the class KDoc.
     */
    val readPatterns: Set<String> = emptySet(),

    /**
     * Glob patterns for files the agent can write.
     * Empty set means no write access — see the class KDoc.
     */
    val writePatterns: Set<String> = emptySet(),

    /**
     * Glob patterns that are always blocked, regardless of other patterns.
     * Takes precedence over read/write patterns.
     */
    val forbiddenPatterns: Set<String> = emptySet(),
) {
    companion object {
        /** The pattern that matches every path with at least one segment. */
        const val AnyPath: String = "**/*"

        /**
         * Permissive scope that allows reading and writing any file.
         * Typically used as a starting point before Sparks narrow access.
         */
        val Permissive = FileAccessScope(
            readPatterns = setOf(AnyPath),
            writePatterns = setOf(AnyPath),
            forbiddenPatterns = emptySet(),
        )

        /**
         * Read-only scope that allows reading any file but no writing.
         */
        val ReadOnly = FileAccessScope(
            readPatterns = setOf(AnyPath),
            writePatterns = emptySet(),
            forbiddenPatterns = emptySet(),
        )

        /**
         * No access scope - blocks all file operations.
         */
        val NoAccess = FileAccessScope(
            readPatterns = emptySet(),
            writePatterns = emptySet(),
            forbiddenPatterns = setOf(AnyPath),
        )

        /**
         * Common forbidden patterns for sensitive files.
         */
        val SensitiveFileForbiddenPatterns = setOf(
            "**/.env",
            "**/.env.*",
            "**/credentials.json",
            "**/secrets.json",
            "**/*.pem",
            "**/*.key",
            "**/id_rsa*",
            "**/.git/config",
        )

        /**
         * True when [path] matches the glob [pattern].
         *
         * Pure `commonMain`: `java.nio.file.PathMatcher` is JVM-only and
         * `ampere-core` also targets Android, iOS, JS and wasmJs.
         *
         * Both arguments are normalized the same way before matching, so a
         * leading `./`, a leading `/`, a trailing `/`, a doubled separator and
         * a `.` segment are all insignificant: `./src/Foo.kt`, `src/Foo.kt`
         * and `src//Foo.kt` are the same path. A `..` segment is *not*
         * resolved — path containment is the workspace's job (AMPR-300), and
         * resolving it here would quietly bless a pattern match on a path that
         * escapes the workspace.
         */
        fun matches(pattern: String, path: String): Boolean =
            matchSegments(
                patternSegments = segmentsOf(pattern),
                patternIndex = 0,
                pathSegments = segmentsOf(path),
                pathIndex = 0,
            )

        /**
         * True when every path matched by [narrower] is also matched by
         * [broader] — i.e. `broader` is the weaker constraint of the two and
         * `narrower` is what their conjunction should keep.
         *
         * This is the subsumption test [intersect] composes with, and it is
         * **sound but deliberately incomplete**: it never reports a
         * containment that does not hold, but it does fail to spot some that
         * do (`*a*` over `*ab*`, for instance). Incompleteness only
         * ever drops a pattern from an intersection, which narrows; a false
         * positive here would widen an agent's reach, which is the one thing
         * the narrowing invariant forbids. Deciding glob containment exactly
         * means deciding language containment of two regular languages — far
         * more machinery than a permission check should carry.
         *
         * The rules:
         * - identical patterns subsume each other
         * - a pattern of nothing but `**` segments matches every path, the
         *   empty one included, so it subsumes anything; [AnyPath] subsumes
         *   anything that cannot itself match the empty path, which is to say
         *   anything with a segment that is not `**`
         *
         * and then, per aligned segment:
         * - `*` and `**` subsume any single segment pattern
         * - a `**` segment on the broader side may absorb any run of segments
         *   on the narrower side; nothing *but* a `**` can absorb a `**`
         * - against a narrower segment with no wildcard, the broader segment
         *   just has to match it as a literal
         * - against a narrower segment that has wildcards, a broader segment
         *   of the shape `prefix*suffix` subsumes it when the narrower's
         *   leading literal starts with `prefix` and its trailing literal ends
         *   with `suffix` (so `*.kt` subsumes `*Test.kt`, but not `*.kts`)
         */
        fun subsumes(broader: String, narrower: String): Boolean {
            if (broader == narrower) return true

            val broaderSegments = segmentsOf(broader)
            val narrowerSegments = segmentsOf(narrower)

            // Nothing but `**` matches every path there is.
            if (broaderSegments.isNotEmpty() && broaderSegments.all { it == DoubleStar }) return true
            // `**/*` matches every path with at least one segment, and only an
            // all-`**` pattern can match one with none, so it is the universal
            // pattern for every path a file tool could be handed. Worth a rule
            // of its own: it is what a spark writes to say "no constraint",
            // and the segment walk below cannot see past the trailing `*`.
            if (broaderSegments == AnyPathSegments && narrowerSegments.any { it != DoubleStar }) {
                return true
            }

            return segmentsSubsume(
                broaderSegments = broaderSegments,
                broaderIndex = 0,
                narrowerSegments = narrowerSegments,
                narrowerIndex = 0,
            )
        }

        private const val DoubleStar = "**"

        private val AnyPathSegments = listOf(DoubleStar, "*")

        /**
         * The significant segments of a path or pattern: empty segments (from
         * a leading, trailing or doubled `/`) and `.` segments carry no
         * meaning and are dropped.
         */
        private fun segmentsOf(value: String): List<String> =
            value.split('/').filter { it.isNotEmpty() && it != "." }

        private fun matchSegments(
            patternSegments: List<String>,
            patternIndex: Int,
            pathSegments: List<String>,
            pathIndex: Int,
        ): Boolean {
            if (patternIndex == patternSegments.size) return pathIndex == pathSegments.size

            if (patternSegments[patternIndex] == DoubleStar) {
                // `**` matches zero or more segments: try every split.
                for (consumed in pathIndex..pathSegments.size) {
                    if (matchSegments(patternSegments, patternIndex + 1, pathSegments, consumed)) {
                        return true
                    }
                }
                return false
            }

            if (pathIndex == pathSegments.size) return false
            if (!matchSegment(patternSegments[patternIndex], pathSegments[pathIndex])) return false
            return matchSegments(patternSegments, patternIndex + 1, pathSegments, pathIndex + 1)
        }

        /** Matches one pattern segment against one path segment. */
        private fun matchSegment(pattern: String, segment: String): Boolean =
            matchSegmentFrom(pattern, 0, segment, 0)

        private fun matchSegmentFrom(
            pattern: String,
            patternStart: Int,
            segment: String,
            segmentStart: Int,
        ): Boolean {
            var p = patternStart
            var s = segmentStart
            while (p < pattern.length) {
                when (val token = pattern[p]) {
                    '*' -> {
                        // Within a segment `**` is no stronger than `*`.
                        var next = p + 1
                        while (next < pattern.length && pattern[next] == '*') next++
                        if (next == pattern.length) return true
                        for (consumed in s..segment.length) {
                            if (matchSegmentFrom(pattern, next, segment, consumed)) return true
                        }
                        return false
                    }

                    '?' -> {
                        if (s == segment.length) return false
                        s++
                        p++
                    }

                    '[' -> {
                        val close = characterClassEnd(pattern, p)
                        if (close < 0) {
                            // Unterminated class: the bracket is a literal.
                            if (s == segment.length || segment[s] != '[') return false
                            s++
                            p++
                        } else {
                            if (s == segment.length) return false
                            if (!characterClassMatches(pattern.substring(p + 1, close), segment[s])) {
                                return false
                            }
                            s++
                            p = close + 1
                        }
                    }

                    else -> {
                        if (s == segment.length || segment[s] != token) return false
                        s++
                        p++
                    }
                }
            }
            return s == segment.length
        }

        /** Index of the `]` closing the class opened at [open], or -1 if unterminated. */
        private fun characterClassEnd(pattern: String, open: Int): Int {
            var index = open + 1
            if (index < pattern.length && (pattern[index] == '!' || pattern[index] == '^')) index++
            // A `]` immediately after the (negated) opening is a literal member.
            if (index < pattern.length && pattern[index] == ']') index++
            while (index < pattern.length) {
                if (pattern[index] == ']') return index
                index++
            }
            return -1
        }

        private fun characterClassMatches(body: String, char: Char): Boolean {
            if (body.isEmpty()) return false
            var index = 0
            val negated = body[0] == '!' || body[0] == '^'
            if (negated) index = 1

            var matched = false
            while (index < body.length) {
                if (index + 2 < body.length && body[index + 1] == '-') {
                    if (char in body[index]..body[index + 2]) matched = true
                    index += 3
                } else {
                    if (body[index] == char) matched = true
                    index++
                }
            }
            return matched != negated
        }

        private fun segmentsSubsume(
            broaderSegments: List<String>,
            broaderIndex: Int,
            narrowerSegments: List<String>,
            narrowerIndex: Int,
        ): Boolean {
            if (broaderIndex == broaderSegments.size) {
                return narrowerIndex == narrowerSegments.size
            }

            if (broaderSegments[broaderIndex] == DoubleStar) {
                // A `**` on the broader side absorbs any run of narrower
                // segments, a `**` of its own included.
                for (consumed in narrowerIndex..narrowerSegments.size) {
                    if (segmentsSubsume(broaderSegments, broaderIndex + 1, narrowerSegments, consumed)) {
                        return true
                    }
                }
                return false
            }

            if (narrowerIndex == narrowerSegments.size) return false
            // Only a `**` can cover an unbounded run of segments; a single
            // segment token provably cannot.
            if (narrowerSegments[narrowerIndex] == DoubleStar) return false
            if (!segmentSubsumes(broaderSegments[broaderIndex], narrowerSegments[narrowerIndex])) {
                return false
            }
            return segmentsSubsume(
                broaderSegments,
                broaderIndex + 1,
                narrowerSegments,
                narrowerIndex + 1,
            )
        }

        private fun segmentSubsumes(broader: String, narrower: String): Boolean {
            if (broader == narrower) return true
            if (broader == "*" || broader == DoubleStar) return true
            // A narrower segment with no wildcards is a literal, so matching
            // it is exactly subsumption.
            if (!hasWildcard(narrower)) return matchSegment(broader, narrower)

            // Both sides carry wildcards. The one sound case worth keeping:
            // the broader side is a single `*` between two literals.
            val star = broader.indexOf('*')
            if (star < 0) return false
            if (broader.indexOf('*', star + 1) >= 0) return false
            if (broader.any { it == '?' || it == '[' }) return false

            val requiredPrefix = broader.substring(0, star)
            val requiredSuffix = broader.substring(star + 1)
            return literalPrefix(narrower).startsWith(requiredPrefix) &&
                literalSuffix(narrower).endsWith(requiredSuffix)
        }

        private fun hasWildcard(segment: String): Boolean =
            segment.any { it == '*' || it == '?' || it == '[' }

        /** The literal characters every match of [segment] must start with. */
        private fun literalPrefix(segment: String): String {
            val firstWildcard = segment.indexOfFirst { it == '*' || it == '?' || it == '[' }
            return if (firstWildcard < 0) segment else segment.substring(0, firstWildcard)
        }

        /** The literal characters every match of [segment] must end with. */
        private fun literalSuffix(segment: String): String {
            val lastWildcard = segment.indexOfLast { it == '*' || it == '?' || it == '[' }
            return if (lastWildcard < 0) segment else segment.substring(lastWildcard + 1)
        }

        /**
         * The conjunction of two pattern sets: the patterns describing exactly
         * the paths both sides allow, as far as [subsumes] can tell.
         *
         * Literal set intersection over the pattern *strings* was the AMPR-414
         * bug: intersecting [AnyPath] with a Kotlin-extension pattern came
         * out *empty*, because the two strings are not equal — never mind that
         * the first subsumes the second — so composing a role spark with a
         * language spark denied everything. Pairing the two sides and keeping
         * the narrower of each subsuming pair fixes that without widening —
         * every pattern kept is one of the two inputs, and it is the one whose
         * paths both sides already allow.
         *
         * A pair where neither pattern subsumes the other contributes nothing,
         * even when the two overlap: a directory pattern rooted at `src` and a
         * depth-free Kotlin-extension pattern both match `src/Foo.kt`, and
         * their pair is still dropped. That is the incompleteness of
         * [subsumes] showing through, and it errs toward denial.
         */
        private fun intersectPatterns(left: Set<String>, right: Set<String>): Set<String> {
            if (left.isEmpty() || right.isEmpty()) return emptySet()
            val narrowed = mutableSetOf<String>()
            for (leftPattern in left) {
                for (rightPattern in right) {
                    when {
                        subsumes(leftPattern, rightPattern) -> narrowed += rightPattern
                        subsumes(rightPattern, leftPattern) -> narrowed += leftPattern
                    }
                }
            }
            return narrowed
        }
    }

    /**
     * The forbidden pattern blocking [path], or null when none does.
     *
     * Exposed rather than folded into [allowsRead] / [allowsWrite] so a
     * refusal can say *which* rule refused — naming the build-output pattern
     * that blocked a path is actionable where "not permitted" is not.
     */
    fun forbiddingPattern(path: String): String? =
        forbiddenPatterns.firstOrNull { matches(it, path) }

    /** True when some [forbiddenPatterns] entry blocks [path]. */
    fun isForbidden(path: String): Boolean = forbiddingPattern(path) != null

    /**
     * True when [path] may be read: some [readPatterns] entry matches it and
     * no [forbiddenPatterns] entry does. Forbidden always wins.
     */
    fun allowsRead(path: String): Boolean = allows(readPatterns, path)

    /**
     * True when [path] may be written: some [writePatterns] entry matches it
     * and no [forbiddenPatterns] entry does. Forbidden always wins.
     */
    fun allowsWrite(path: String): Boolean = allows(writePatterns, path)

    private fun allows(patterns: Set<String>, path: String): Boolean {
        if (isForbidden(path)) return false
        return patterns.any { matches(it, path) }
    }

    /**
     * Combines this scope with another using intersection semantics.
     *
     * - Read patterns: intersection (can only read what both allow)
     * - Write patterns: intersection (can only write what both allow)
     * - Forbidden patterns: union (blocked by either means blocked)
     *
     * Intersection is subsumption-aware — see [intersectPatterns]. The
     * operation is monotone in both arguments: for every path,
     * `a.intersect(b).allowsWrite(p)` implies `a.allowsWrite(p) &&
     * b.allowsWrite(p)` (and likewise for reads), so adding a Spark to a
     * stack can only ever take access away.
     */
    fun intersect(other: FileAccessScope): FileAccessScope = FileAccessScope(
        readPatterns = intersectPatterns(this.readPatterns, other.readPatterns),
        writePatterns = intersectPatterns(this.writePatterns, other.writePatterns),
        forbiddenPatterns = this.forbiddenPatterns.union(other.forbiddenPatterns),
    )
}
