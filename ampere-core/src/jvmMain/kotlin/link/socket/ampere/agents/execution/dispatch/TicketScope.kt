package link.socket.ampere.agents.execution.dispatch

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable

/**
 * What a ticket declares it may write: its `ampere-scope` block, read (AMPR-306, M1).
 *
 * There is no "empty" member, on purpose. A ticket with no block, or a block that
 * does not parse, has [Unknown] scope — and unknown scope overlaps everything, so
 * such a ticket can only be dispatched alone. That is the TicketConventions
 * invariant that makes lazy retrofitting safe rather than silently unsafe: the one
 * failure mode that would look like success (a block that fails to parse reading as
 * "writes nothing") cannot be expressed.
 */
sealed interface TicketScope {

    /**
     * A block that parsed and validated.
     *
     * @property exclusive False asserts this ticket's writes commute with an
     *   overlapping ticket's. Absent means true.
     */
    data class Declared(
        val repos: List<RepoScope>,
        val exclusive: Boolean = true,
    ) : TicketScope {

        /** The entry for [repository] (`owner/name`, case-insensitive), or null. */
        fun entryFor(repository: String): RepoScope? =
            repos.firstOrNull { it.repo.equals(repository, ignoreCase = true) }
    }

    /** No readable block. [reason] says which way it was unreadable. */
    data class Unknown(val reason: String) : TicketScope
}

/** One repository a ticket may write, and the repo-relative globs within it. */
data class RepoScope(
    val repo: String,
    val paths: List<String>,
)

/**
 * Reads `ampere-scope` blocks and decides whether two scopes overlap.
 *
 * ### Overlap fails toward "yes"
 *
 * Exact glob intersection is not decidable cheaply, and the matchers in this
 * repository do not even agree on what `*` means (TicketConventions: `**` versus
 * `*` is an authoring rule, not a matcher feature). So [overlap] over-approximates:
 * it answers "disjoint" only when it can prove no path matches both globs under any
 * reading, and "overlapping" otherwise. A false "overlapping" is a refused dispatch —
 * visible and cheap. A false "disjoint" is two agents writing one file.
 *
 * The proof it uses: every path a glob matches starts with the glob's literal prefix
 * (everything before its first wildcard), so two globs whose literal prefixes diverge
 * cannot share a path. A literal path is also read as covering everything beneath it,
 * because a bare directory written by an author who meant a subtree is the common
 * mistake the conventions warn about, and reading it narrowly would be the unsafe
 * direction.
 */
object TicketScopes {

    /** The fence info string that marks a scope block. */
    const val FENCE_TAG: String = "ampere-scope"

    /** Reads the scope [description] declares. Never throws. */
    fun parse(description: String?): TicketScope {
        if (description.isNullOrBlank()) return TicketScope.Unknown("the ticket has no description")

        val blocks = fencedBlocks(description)
        val block = when (blocks.size) {
            0 -> return TicketScope.Unknown("the description has no ```$FENCE_TAG block")
            1 -> blocks.single()
            else -> return TicketScope.Unknown(
                "the description has ${blocks.size} ```$FENCE_TAG blocks; exactly one is allowed",
            )
        }

        val file = try {
            YAML.decodeFromString(ScopeFile.serializer(), block)
        } catch (e: Exception) {
            val why = e.message?.lineSequence()?.firstOrNull()
            return TicketScope.Unknown("the ```$FENCE_TAG block is not valid: $why")
        }

        return validate(file)
    }

    /**
     * Why [a] and [b] may write the same file, or null when they provably cannot.
     *
     * Unknown on either side overlaps. Two declared scopes that both set
     * `exclusive: false` never do: each asserted its writes commute, and only then is
     * the assertion mutual. One exclusive side is enough to refuse, because that
     * ticket asked for its paths to itself.
     */
    fun overlap(a: TicketScope, b: TicketScope): String? {
        val first = when (a) {
            is TicketScope.Unknown -> return "unknown scope (${a.reason})"
            is TicketScope.Declared -> a
        }
        val second = when (b) {
            is TicketScope.Unknown -> return "unknown scope (${b.reason})"
            is TicketScope.Declared -> b
        }
        if (!first.exclusive && !second.exclusive) return null

        first.repos.forEach { left ->
            val right = second.entryFor(left.repo) ?: return@forEach
            left.paths.forEach { p ->
                right.paths.forEach { q ->
                    if (mayOverlap(p, q)) return "${left.repo}: '$p' and '$q'"
                }
            }
        }
        return null
    }

    /** Whether two repo-relative globs may match a common path. Over-approximate; see the class KDoc. */
    fun mayOverlap(a: String, b: String): Boolean {
        val left = a.trim().trimEnd('/')
        val right = b.trim().trimEnd('/')
        val leftPrefix = literalPrefix(left)
        val rightPrefix = literalPrefix(right)
        val leftLiteral = leftPrefix.length == left.length
        val rightLiteral = rightPrefix.length == right.length

        return when {
            leftLiteral && rightLiteral ->
                left == right || right.startsWith("$left/") || left.startsWith("$right/")

            leftLiteral -> literalMeetsGlob(left, right, rightPrefix)
            rightLiteral -> literalMeetsGlob(right, left, leftPrefix)
            else -> leftPrefix.startsWith(rightPrefix) || rightPrefix.startsWith(leftPrefix)
        }
    }

    /** A literal path, read as itself plus its subtree, against a glob. */
    private fun literalMeetsGlob(literal: String, glob: String, globPrefix: String): Boolean {
        if (globRegex(glob).matches(literal)) return true
        val subtree = "$literal/"
        return globPrefix.startsWith(subtree) || subtree.startsWith(globPrefix)
    }

    /**
     * The glob as a regex that matches a superset of what any reading of it matches:
     * every wildcard may cross `/`, a class is any one character, an alternation is
     * anything.
     */
    private fun globRegex(glob: String): Regex {
        val pattern = buildString {
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    '[' -> {
                        append(".")
                        i = glob.indexOf(']', i).takeIf { it >= 0 } ?: glob.length
                    }
                    '{' -> {
                        append(".*")
                        i = glob.indexOf('}', i).takeIf { it >= 0 } ?: glob.length
                    }
                    else -> append(Regex.escape(c.toString()))
                }
                i++
            }
        }
        return Regex(pattern)
    }

    private fun literalPrefix(glob: String): String {
        val end = glob.indexOfFirst { it in WILDCARDS }
        return if (end < 0) glob else glob.substring(0, end)
    }

    private fun validate(file: ScopeFile): TicketScope {
        if (file.repos.isEmpty()) return TicketScope.Unknown("`repos` is empty")

        file.repos.forEach { entry ->
            val parts = entry.repo.split('/')
            if (parts.size != 2 || parts.any { it.isBlank() }) {
                return TicketScope.Unknown("'${entry.repo}' is not an <owner>/<name> repository")
            }
            if (entry.paths.isEmpty()) {
                return TicketScope.Unknown("${entry.repo} declares no paths")
            }
            entry.paths.forEach { path ->
                val problem = when {
                    path.isBlank() -> "a blank path"
                    path.startsWith("/") -> "an absolute path"
                    path.split('/').any { it == ".." } -> "a path that climbs out with '..'"
                    else -> null
                }
                if (problem != null) return TicketScope.Unknown("${entry.repo} declares $problem: '$path'")
            }
        }

        return TicketScope.Declared(
            repos = file.repos.map { RepoScope(it.repo, it.paths) },
            exclusive = file.exclusive,
        )
    }

    /**
     * Bodies of every fenced block whose info string is [FENCE_TAG], with backtick or
     * tilde fences of three or more characters.
     */
    private fun fencedBlocks(markdown: String): List<String> {
        val blocks = mutableListOf<String>()
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val opening = FENCE.matchEntire(lines[i].trimEnd())
            if (opening == null) {
                i++
                continue
            }
            val fence = opening.groupValues[1]
            val info = opening.groupValues[2].trim()
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && !isClosing(lines[i], fence)) {
                body += lines[i]
                i++
            }
            i++
            if (info == FENCE_TAG) blocks += body.joinToString("\n")
        }
        return blocks
    }

    private fun isClosing(line: String, fence: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= fence.length && trimmed.all { it == fence[0] }
    }

    private val FENCE = Regex("""\s{0,3}(`{3,}|~{3,})(.*)""")

    private val WILDCARDS = setOf('*', '?', '[', '{')

    /**
     * Strict, so a misspelt key (`exlusive: false`) fails the block — and an unknown
     * scope — rather than being ignored into an exclusive one the author did not mean.
     */
    private val YAML = Yaml(configuration = YamlConfiguration(strictMode = true))

    @Serializable
    private data class ScopeFile(
        val repos: List<ScopeRepo> = emptyList(),
        val exclusive: Boolean = true,
    )

    @Serializable
    private data class ScopeRepo(
        val repo: String,
        val paths: List<String> = emptyList(),
    )
}
