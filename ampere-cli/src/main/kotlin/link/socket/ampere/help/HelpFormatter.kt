package link.socket.ampere.help

import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim

/**
 * Utilities for formatting CLI help text with consistent styling and layout.
 *
 * This formatter ensures all help output follows the same visual hierarchy and structure,
 * making the CLI more predictable and demo-friendly.
 */
object HelpFormatter {

    // Column widths for aligned command layouts
    const val COMMAND_WIDTH = 40
    const val DESCRIPTION_WIDTH = 50
    const val INDENT = "  "
    const val DOUBLE_INDENT = "    "

    /**
     * Format a command with its description, ensuring consistent column alignment.
     *
     * @param syntax The command syntax (e.g., "watch [--filter TYPE]")
     * @param description Action-oriented description of what the command does
     * @return Formatted string with aligned columns
     */
    fun formatCommand(syntax: String, description: String): String {
        return INDENT + syntax.padEnd(COMMAND_WIDTH) + description
    }

    /**
     * Format a section header with optional tagline.
     *
     * @param title The section title (e.g., "OBSERVE")
     * @param tagline Optional explanatory tagline for the section
     * @return Formatted section header with separator
     */
    fun formatSection(title: String, tagline: String? = null): String {
        val separator = "─".repeat(50)
        return buildString {
            appendLine("─── $title $separator".take(60))
            if (tagline != null) {
                appendLine(dim(tagline))
            }
        }
    }

    /**
     * Format a section header with bold styling and tagline.
     *
     * @param title The section title
     * @param tagline Explanatory text for the section
     * @return Formatted section with styling
     */
    fun formatSectionWithTagline(title: String, tagline: String): String {
        return buildString {
            appendLine()
            appendLine(bold(cyan(title)))
            appendLine(gray(tagline))
            appendLine()
        }
    }

    /**
     * Format a key-value pair with consistent indentation.
     * Used for showing flag definitions, valid values, etc.
     *
     * @param key The key (e.g., "-f, --filter")
     * @param value The value description
     * @param indent The indentation level (default: DOUBLE_INDENT)
     * @return Formatted key-value string
     */
    fun formatKeyValue(key: String, value: String, indent: String = DOUBLE_INDENT): String {
        return "$indent$key".padEnd(COMMAND_WIDTH + 4) + value
    }

    /**
     * Format a list of valid values for a parameter.
     *
     * @param values List of valid values
     * @param perLine Number of values per line
     * @return Formatted string with values wrapped appropriately
     */
    fun formatValidValues(values: List<String>, perLine: Int = 3): String {
        return values.chunked(perLine).joinToString("\n") { chunk ->
            DOUBLE_INDENT + chunk.joinToString("  ") { it.padEnd(20) }
        }
    }

    /**
     * Format an example command with optional explanation.
     *
     * @param command The example command
     * @param explanation Optional explanation of what the command does
     * @return Formatted example
     */
    fun formatExample(command: String, explanation: String? = null): String {
        return buildString {
            append(DOUBLE_INDENT)
            append(command.padEnd(45))
            if (explanation != null) {
                append("# $explanation")
            }
        }
    }

    /**
     * Build a complete help text section with consistent formatting.
     *
     * @param commandName Name of the command
     * @param purpose What the command does (action-oriented)
     * @param usage Syntax usage string
     * @param flags List of flag descriptions
     * @param examples List of example commands
     * @param additionalSections Map of section title to content
     * @return Complete formatted help text
     */
    fun buildDetailedHelp(
        commandName: String,
        purpose: String,
        usage: String,
        flags: List<Pair<String, String>> = emptyList(),
        examples: List<Pair<String, String?>> = emptyList(),
        additionalSections: Map<String, String> = emptyMap()
    ): String = buildString {
        // Header
        appendLine(bold(cyan(commandName.uppercase())) + " - $purpose")
        appendLine()

        // Usage
        appendLine(bold("Usage:"))
        appendLine(INDENT + usage)
        appendLine()

        // Flags (if any)
        if (flags.isNotEmpty()) {
            appendLine(bold("Options:"))
            flags.forEach { (flag, desc) ->
                appendLine(formatKeyValue(flag, desc))
            }
            appendLine()
        }

        // Additional sections (e.g., "Valid Event Types", "Valid Statuses")
        additionalSections.forEach { (title, content) ->
            appendLine(bold("$title:"))
            appendLine(content)
            appendLine()
        }

        // Examples (if any)
        if (examples.isNotEmpty()) {
            appendLine(bold("Examples:"))
            examples.forEach { (command, explanation) ->
                appendLine(formatExample(command, explanation))
            }
            appendLine()
        }
    }

    /**
     * Build the main help overview with sections for different command categories.
     *
     * @param sections Map of section title to list of command definitions
     * @return Formatted main help text
     */
    fun buildMainHelp(
        sections: Map<String, List<Triple<String, String, String?>>>,
        footer: String? = null
    ): String = buildString {
        appendLine(bold(cyan("AMPERE - Autonomous Agent Coordination")))
        appendLine()

        sections.forEach { (sectionTitle, commands) ->
            // Section header with tagline (if provided)
            val parts = sectionTitle.split("|")
            val title = parts[0]
            val tagline = parts.getOrNull(1)

            appendLine(formatSection(title, tagline))
            appendLine()

            // Commands in this section
            commands.forEach { (syntax, description, _) ->
                appendLine(formatCommand(syntax, description))
            }
            appendLine()
        }

        // Footer (if provided)
        if (footer != null) {
            appendLine(footer)
        }
    }

    /**
     * Create a compact, demo-friendly status line.
     *
     * @param agentCount Number of active agents
     * @param workspacePath Path to workspace being monitored
     * @return Formatted status line
     */
    fun formatStartupStatus(agentCount: Int, workspacePath: String): String {
        return "⚡ Ampere ready | $agentCount agents | monitoring: $workspacePath"
    }

    /**
     * Format a key binding description.
     *
     * @param key The key or key combination (e.g., "Enter", "Ctrl+C")
     * @param action What the key does
     * @return Formatted key binding
     */
    fun formatKeyBinding(key: String, action: String): String {
        return key.padEnd(12) + action
    }
}
