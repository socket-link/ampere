package link.socket.ampere.agents.definition.code

import link.socket.ampere.integrations.issues.ExistingIssue

/**
 * Git workflow helper functions for CodeAgent.
 *
 * These functions generate branch names, commit messages, and PR content
 * following conventions and best practices for the issue-to-branch workflow.
 */
object CodeAgentGitHelpers {

    /**
     * Generates a branch name from an issue following the convention:
     * `feature/{issue-number}-{slug}`
     *
     * Examples:
     * - Issue #123 "Add user authentication" → "feature/123-add-user-authentication"
     * - Issue #456 "Fix: Database connection timeout" → "feature/456-fix-database-connection-timeout"
     *
     * @param issue The GitHub issue
     * @param prefix Branch prefix (default: "feature")
     * @return Formatted branch name
     */
    fun generateBranchName(
        issue: ExistingIssue,
        prefix: String = "feature",
    ): String {
        // Calculate max slug length to keep total branch name reasonable
        // Format: {prefix}/{number}-{slug}
        val prefixLength = prefix.length + 1 // "feature/"
        val numberLength = issue.number.toString().length + 1 // "123-"
        val maxSlugLength = 50 - prefixLength - numberLength

        val slug = issue.title
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .take(maxSlugLength.coerceAtLeast(10)) // At least 10 chars for slug
            .trim('-')
        return "$prefix/${issue.number}-$slug"
    }

    /**
     * Generates a conventional commit message from an issue and changes.
     *
     * Format: `{type}({scope}): {description}`
     *
     * Examples:
     * - "feat(auth): add user authentication"
     * - "fix(database): resolve connection timeout"
     * - "test(api): add endpoint validation tests"
     *
     * @param issue The GitHub issue
     * @param changedFiles List of files that were changed
     * @return Conventional commit message
     */
    fun generateCommitMessage(
        issue: ExistingIssue,
        changedFiles: List<String> = emptyList(),
    ): String {
        val type = determineCommitType(issue, changedFiles)
        val scope = detectScope(changedFiles)
        val description = issue.title
            .lowercase()
            .removePrefix("feat:")
            .removePrefix("fix:")
            .removePrefix("test:")
            .removePrefix("docs:")
            .removePrefix("refactor:")
            .removePrefix("chore:")
            .trim()
            .take(50)

        return if (scope.isNotBlank()) {
            "$type($scope): $description"
        } else {
            "$type: $description"
        }
    }

    /**
     * Generates a PR title from an issue.
     *
     * Uses the issue title directly, as it already describes the change.
     *
     * @param issue The GitHub issue
     * @return PR title
     */
    fun generatePRTitle(issue: ExistingIssue): String {
        return issue.title
    }

    /**
     * Generates a PR body/description from an issue and changes.
     *
     * Format:
     * ```
     * ## Summary
     * {issue body summary}
     *
     * ## Changes
     * - {file changes summary}
     *
     * ## Testing
     * - [ ] {test checklist items}
     *
     * Closes #{issue.number}
     * ```
     *
     * @param issue The GitHub issue
     * @param changedFiles List of files that were changed
     * @return Formatted PR body
     */
    fun generatePRBody(
        issue: ExistingIssue,
        changedFiles: List<String> = emptyList(),
    ): String = buildString {
        appendLine("## Summary")
        appendLine()

        // Include issue body summary
        if (issue.body.isNotBlank()) {
            val summary = issue.body.lines().take(5).joinToString("\n")
            appendLine(summary)
            if (issue.body.lines().size > 5) {
                appendLine("...")
            }
        } else {
            appendLine("Implements ${issue.title}")
        }

        appendLine()
        appendLine("## Changes")
        appendLine()

        if (changedFiles.isNotEmpty()) {
            val groupedFiles = groupFilesByCategory(changedFiles)
            groupedFiles.forEach { (category, files) ->
                appendLine("**$category:**")
                files.take(10).forEach { file ->
                    appendLine("- `$file`")
                }
                if (files.size > 10) {
                    appendLine("- ... and ${files.size - 10} more")
                }
                appendLine()
            }
        } else {
            appendLine("- Implementation details will be added during development")
            appendLine()
        }

        appendLine("## Testing")
        appendLine()
        appendLine("- [ ] Code compiles without errors")
        appendLine("- [ ] All existing tests pass")

        if (hasTestFiles(changedFiles)) {
            appendLine("- [ ] New tests added and passing")
        }

        appendLine("- [ ] Manual testing completed")
        appendLine()

        appendLine("Closes #${issue.number}")
    }

    /**
     * Generates reviewers list for a PR.
     *
     * By default, assigns QATestingAgent for code review.
     * Can be extended to analyze issue labels or complexity for smart reviewer assignment.
     *
     * @param issue The GitHub issue
     * @return List of reviewer usernames
     */
    fun generateReviewers(issue: ExistingIssue): List<String> {
        val reviewers = mutableListOf<String>()

        // Always add QA for code review
        reviewers.add("QATestingAgent")

        // Add security reviewer if security-related
        if (issue.labels.any { it.contains("security", ignoreCase = true) }) {
            reviewers.add("SecurityReviewAgent")
        }

        // Add performance reviewer if performance-related
        if (issue.labels.any { it.contains("performance", ignoreCase = true) }) {
            reviewers.add("PerformanceOptimizationAgent")
        }

        return reviewers.distinct()
    }

    // ========================================================================
    // Private Helper Functions
    // ========================================================================

    /**
     * Determines the conventional commit type based on issue and changes.
     */
    private fun determineCommitType(
        issue: ExistingIssue,
        changedFiles: List<String>,
    ): String {
        // Check issue labels first
        return when {
            issue.labels.any { it.equals("bug", ignoreCase = true) } -> "fix"
            issue.labels.any { it.equals("feature", ignoreCase = true) } -> "feat"
            issue.labels.any { it.equals("documentation", ignoreCase = true) } -> "docs"
            issue.labels.any { it.equals("refactor", ignoreCase = true) } -> "refactor"
            issue.labels.any { it.equals("test", ignoreCase = true) } -> "test"
            issue.labels.any { it.equals("chore", ignoreCase = true) } -> "chore"

            // Check issue title
            issue.title.startsWith("fix:", ignoreCase = true) -> "fix"
            issue.title.startsWith("feat:", ignoreCase = true) -> "feat"
            issue.title.startsWith("add", ignoreCase = true) -> "feat"
            issue.title.contains("fix", ignoreCase = true) -> "fix"

            // Check files
            changedFiles.all { it.contains("test", ignoreCase = true) || it.contains("spec", ignoreCase = true) } -> "test"
            changedFiles.all { it.endsWith(".md") } -> "docs"

            // Default to feat for new functionality
            else -> "feat"
        }
    }

    /**
     * Detects the scope from changed files.
     *
     * Scope is typically the module, package, or component being changed.
     */
    private fun detectScope(changedFiles: List<String>): String {
        if (changedFiles.isEmpty()) return ""

        // Try to find common directory/package
        val commonParts = findCommonPathParts(changedFiles)

        return when {
            // Use the last common directory (most specific)
            commonParts.isNotEmpty() -> {
                // Skip generic parts like "src", "main", "kotlin", "java"
                val meaningfulParts = commonParts.filterNot {
                    it in setOf("src", "main", "test", "kotlin", "java", "commonMain", "jvmMain", "androidMain")
                }
                meaningfulParts.lastOrNull()
                    ?.replace("-", "")
                    ?.take(15)
                    ?: ""
            }

            // Single file - use filename
            changedFiles.size == 1 -> changedFiles[0]
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace("-", "")
                .take(15)

            // Multiple files with no common path
            else -> ""
        }
    }

    /**
     * Finds common path parts among files.
     */
    private fun findCommonPathParts(files: List<String>): List<String> {
        if (files.isEmpty()) return emptyList()

        val parts = files.map { it.split('/') }
        val minLength = parts.minOf { it.size }

        val common = mutableListOf<String>()
        for (i in 0 until minLength) {
            val part = parts[0][i]
            if (parts.all { it[i] == part }) {
                common.add(part)
            } else {
                break
            }
        }

        return common
    }

    /**
     * Groups files by category (source, tests, docs, etc.).
     */
    private fun groupFilesByCategory(files: List<String>): Map<String, List<String>> {
        return files.groupBy { file ->
            when {
                file.contains("/test/", ignoreCase = true) ||
                    file.contains("Test.kt", ignoreCase = true) ||
                    file.endsWith("Spec.kt") -> "Tests"

                file.endsWith(".md") || file.contains("/docs/", ignoreCase = true) -> "Documentation"

                file.contains("/config/", ignoreCase = true) ||
                    file.endsWith(".json") ||
                    file.endsWith(".yml") ||
                    file.endsWith(".yaml") -> "Configuration"

                file.contains("/build/", ignoreCase = true) ||
                    file.endsWith(".gradle.kts") ||
                    file.endsWith("build.gradle") -> "Build"

                else -> "Source Code"
            }
        }
    }

    /**
     * Checks if the changed files include test files.
     */
    private fun hasTestFiles(changedFiles: List<String>): Boolean {
        return changedFiles.any { file ->
            file.contains("/test/", ignoreCase = true) ||
                file.contains("Test.kt", ignoreCase = true) ||
                file.endsWith("Spec.kt")
        }
    }
}
