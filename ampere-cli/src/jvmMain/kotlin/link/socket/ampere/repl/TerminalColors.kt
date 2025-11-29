package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*

/**
 * Terminal color utilities for consistent visual feedback.
 *
 * Provides semantic coloring for different types of output:
 * - Success: Green with ✓
 * - Error: Red with ✗
 * - Info: Cyan with ℹ
 * - Warning: Yellow with ⚠
 * - Dim: Gray for secondary information
 */
object TerminalColors {
    fun success(message: String) = green("✓ $message")
    fun error(message: String) = red("✗ $message")
    fun info(message: String) = cyan("ℹ $message")
    fun warning(message: String) = yellow("⚠ $message")
    fun dim(message: String) = gray(message)
    fun emphasis(message: String) = bold(message)
}
