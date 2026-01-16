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
 * Standard glob syntax is used:
 * - * matches any characters except path separator
 * - ** matches any characters including path separator (recursive)
 * - ? matches any single character
 * - [abc] matches any character in brackets
 *
 * Examples:
 * - "src/ * * / *.kt" - all Kotlin files under src
 * - "*.md" - all Markdown files in root
 * - "** / .env" - all env files anywhere
 */
@Serializable
data class FileAccessScope(
    /**
     * Glob patterns for files the agent can read.
     * Empty set means no read access unless inherited from parent.
     */
    val readPatterns: Set<String> = emptySet(),

    /**
     * Glob patterns for files the agent can write.
     * Empty set means no write access unless inherited from parent.
     */
    val writePatterns: Set<String> = emptySet(),

    /**
     * Glob patterns that are always blocked, regardless of other patterns.
     * Takes precedence over read/write patterns.
     */
    val forbiddenPatterns: Set<String> = emptySet(),
) {
    companion object {
        /**
         * Permissive scope that allows reading and writing any file.
         * Typically used as a starting point before Sparks narrow access.
         */
        val Permissive = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf("**/*"),
            forbiddenPatterns = emptySet(),
        )

        /**
         * Read-only scope that allows reading any file but no writing.
         */
        val ReadOnly = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = emptySet(),
            forbiddenPatterns = emptySet(),
        )

        /**
         * No access scope - blocks all file operations.
         */
        val NoAccess = FileAccessScope(
            readPatterns = emptySet(),
            writePatterns = emptySet(),
            forbiddenPatterns = setOf("**/*"),
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
    }

    /**
     * Combines this scope with another using intersection semantics.
     *
     * - Read patterns: intersection (can only read what both allow)
     * - Write patterns: intersection (can only write what both allow)
     * - Forbidden patterns: union (blocked by either means blocked)
     */
    fun intersect(other: FileAccessScope): FileAccessScope = FileAccessScope(
        readPatterns = this.readPatterns.intersect(other.readPatterns),
        writePatterns = this.writePatterns.intersect(other.writePatterns),
        forbiddenPatterns = this.forbiddenPatterns.union(other.forbiddenPatterns),
    )
}
