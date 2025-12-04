package link.socket.ampere.agents.receptors

import java.io.File

/**
 * Parsed content from a markdown file.
 */
data class MarkdownContent(
    val title: String,
    val description: String,
    val metadata: Map<String, String> = emptyMap(),
    val contentType: ContentType,
) {
    enum class ContentType {
        FEATURE,
        EPIC,
        PHASE,
        UNKNOWN
    }
}

/**
 * Parser for markdown files containing product specifications.
 *
 * This parser extracts structured information from markdown files
 * following common patterns for feature/epic/phase descriptions.
 *
 * Expected format:
 * ```markdown
 * # Title
 *
 * Description of the feature/epic/phase.
 * Can span multiple paragraphs.
 *
 * ## Metadata (optional)
 * - Act: Act 2
 * - Phase: Phase 1
 * - Epic: Epic Name
 * - Priority: High
 * ```
 */
object MarkdownContentParser {

    /**
     * Parse a markdown file and extract structured content.
     */
    fun parseFile(file: File): MarkdownContent? {
        if (!file.exists() || !file.isFile) {
            return null
        }

        if (file.extension.lowercase() != "md") {
            return null
        }

        val content = file.readText()
        return parseContent(content, file.name, file.absolutePath)
    }

    /**
     * Parse markdown content string.
     */
    fun parseContent(
        content: String,
        fileName: String = "unknown.md",
        filePath: String = ""
    ): MarkdownContent {
        val lines = content.lines()

        // Extract title (first H1 heading)
        val title = extractTitle(lines) ?: fileName.removeSuffix(".md")

        // Extract description (content between title and metadata section)
        val description = extractDescription(lines)

        // Extract metadata (if present)
        val metadata = extractMetadata(lines).toMutableMap()

        // Determine content type from file path and metadata
        val contentType = determineContentType(filePath, metadata, title)

        return MarkdownContent(
            title = title,
            description = description,
            metadata = metadata,
            contentType = contentType
        )
    }

    /**
     * Extract the title from markdown content (first H1 heading).
     */
    private fun extractTitle(lines: List<String>): String? {
        return lines
            .firstOrNull { it.trim().startsWith("# ") }
            ?.trim()
            ?.removePrefix("# ")
            ?.trim()
    }

    /**
     * Extract the description (content after title, before metadata).
     */
    private fun extractDescription(lines: List<String>): String {
        val titleIndex = lines.indexOfFirst { it.trim().startsWith("# ") }
        val metadataIndex = lines.indexOfFirst {
            it.trim().equals("## Metadata", ignoreCase = true)
        }

        val startIndex = if (titleIndex >= 0) titleIndex + 1 else 0
        val endIndex = if (metadataIndex >= 0) metadataIndex else lines.size

        return lines
            .subList(startIndex, endIndex)
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }

    /**
     * Extract metadata from a markdown metadata section.
     *
     * Expected format:
     * ## Metadata
     * - Key: Value
     * - Another Key: Another Value
     */
    private fun extractMetadata(lines: List<String>): Map<String, String> {
        val metadataIndex = lines.indexOfFirst {
            it.trim().equals("## Metadata", ignoreCase = true)
        }

        if (metadataIndex < 0) {
            return emptyMap()
        }

        val metadata = mutableMapOf<String, String>()

        // Parse lines after ## Metadata
        lines.subList(metadataIndex + 1, lines.size)
            .takeWhile { it.isNotBlank() && !it.trim().startsWith("##") }
            .forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                    val cleaned = trimmed.removePrefix("-").removePrefix("*").trim()
                    val parts = cleaned.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        metadata[key] = value
                    }
                }
            }

        return metadata
    }

    /**
     * Determine content type based on file path, metadata, and title.
     */
    private fun determineContentType(
        filePath: String,
        metadata: Map<String, String>,
        title: String
    ): MarkdownContent.ContentType {
        // Check metadata first
        metadata["Type"]?.lowercase()?.let { type ->
            return when {
                type.contains("feature") -> MarkdownContent.ContentType.FEATURE
                type.contains("epic") -> MarkdownContent.ContentType.EPIC
                type.contains("phase") -> MarkdownContent.ContentType.PHASE
                else -> MarkdownContent.ContentType.UNKNOWN
            }
        }

        // Check file path patterns
        val lowerPath = filePath.lowercase()
        return when {
            lowerPath.contains("/feature/") || lowerPath.contains("\\feature\\") ->
                MarkdownContent.ContentType.FEATURE
            lowerPath.contains("/epic/") || lowerPath.contains("\\epic\\") ->
                MarkdownContent.ContentType.EPIC
            lowerPath.contains("/phase/") || lowerPath.contains("\\phase\\") ->
                MarkdownContent.ContentType.PHASE

            // Check title patterns
            title.lowercase().startsWith("feature:") ->
                MarkdownContent.ContentType.FEATURE
            title.lowercase().startsWith("epic:") ->
                MarkdownContent.ContentType.EPIC
            title.lowercase().startsWith("phase:") ->
                MarkdownContent.ContentType.PHASE

            else -> MarkdownContent.ContentType.UNKNOWN
        }
    }
}
