package link.socket.ampere.repl

/**
 * Renders the AMPERE welcome banner with width-aware selection.
 *
 * Selects the appropriate banner variant based on terminal width:
 * - Full banner (49 chars): Used when width >= 80
 * - Compact banner (40 chars): Used when width >= 60 and < 80
 * - Minimal banner (24 chars): Used when width < 60
 *
 * All banners use the Network Diamond design with lightning bolt motifs.
 */
object BannerRenderer {

    /**
     * Width breakpoints for banner selection.
     */
    object Breakpoints {
        const val FULL = 80
        const val COMPACT = 60
        const val MINIMAL = 40
    }

    /**
     * Banner widths for each variant.
     */
    object BannerWidths {
        const val FULL = 49
        const val COMPACT = 40
        const val MINIMAL = 24
    }

    /**
     * Full banner (49 chars wide) - Network Diamond with block lettering.
     * Used when terminal width >= 80 characters.
     */
    private val FULL_BANNER = """
               ⚡──○──⚡
            ⚡──○──⚡──○──⚡
         ⚡──○──⚡──○──⚡──○──⚡
            ⚡──○──⚡──○──⚡
               ⚡──○──⚡
    ░█▀▀█ ░█▀▄▀█ ░█▀▀█ ░█▀▀▀ ░█▀▀█ ░█▀▀▀
    ░█▄▄█ ░█░█░█ ░█▄▄█ ░█▀▀▀ ░█▄▄▀ ░█▀▀▀
    ░█░░░ ░█░░░█ ░█░░░ ░█▄▄▄ ░█░░█ ░█▄▄▄
    """.trimIndent()

    /**
     * Compact banner (40 chars wide) - Simplified Network Diamond.
     * Used when terminal width >= 60 and < 80 characters.
     */
    private val COMPACT_BANNER = """
      ⚡──○──⚡──○──⚡
   ⚡──○──⚡──○──⚡──○──⚡
      ⚡──○──⚡──○──⚡
        A M P E R E
    """.trimIndent()

    /**
     * Minimal banner (24 chars wide) - Single line.
     * Used when terminal width < 60 characters.
     */
    private val MINIMAL_BANNER = "⚡ AMPERE ⚡"

    /**
     * Selects and returns the appropriate banner for the given terminal width.
     *
     * @param width The terminal width in characters
     * @return The banner string appropriate for the given width
     */
    fun selectBanner(width: Int): String {
        return when {
            width >= Breakpoints.FULL -> FULL_BANNER
            width >= Breakpoints.COMPACT -> COMPACT_BANNER
            else -> MINIMAL_BANNER
        }
    }

    /**
     * Selects and returns the appropriate banner using current terminal capabilities.
     *
     * @return The banner string appropriate for the current terminal width
     */
    fun selectBanner(): String {
        val capabilities = TerminalFactory.getCapabilities()
        return selectBanner(capabilities.width)
    }

    /**
     * Returns the full banner variant.
     */
    fun getFullBanner(): String = FULL_BANNER

    /**
     * Returns the compact banner variant.
     */
    fun getCompactBanner(): String = COMPACT_BANNER

    /**
     * Returns the minimal banner variant.
     */
    fun getMinimalBanner(): String = MINIMAL_BANNER

    /**
     * Returns the banner variant type for the given width.
     *
     * @param width The terminal width in characters
     * @return The BannerVariant enum value
     */
    fun getBannerVariant(width: Int): BannerVariant {
        return when {
            width >= Breakpoints.FULL -> BannerVariant.FULL
            width >= Breakpoints.COMPACT -> BannerVariant.COMPACT
            else -> BannerVariant.MINIMAL
        }
    }

    /**
     * Enum representing the different banner variants.
     */
    enum class BannerVariant {
        FULL,
        COMPACT,
        MINIMAL
    }
}
