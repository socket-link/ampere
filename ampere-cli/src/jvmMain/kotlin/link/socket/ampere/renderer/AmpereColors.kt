package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors

/**
 * Ampere brand colors for consistent styling across CLI renderers.
 *
 * The accent color (#24A6DF) is used for:
 * - Agent and system names in event streams
 * - Lightning animation effects
 * - Brand emphasis elements
 *
 * Status indicators (e.g., "✓ PERCEIVE") remain green per convention.
 */
object AmpereColors {
    /**
     * Ampere accent color: #24A6DF
     * A vibrant cyan-blue used for branding and emphasis.
     */
    val accent = TextColors.rgb("#24A6DF")
}
