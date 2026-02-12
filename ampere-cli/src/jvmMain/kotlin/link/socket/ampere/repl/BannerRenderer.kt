package link.socket.ampere.repl

/**
 * Renders the AMPERE welcome banner with width-aware selection.
 *
 * Selects the appropriate banner variant based on terminal width:
 * - Full banner (49 chars): Used when width >= 80
 * - Standard banner: Used when width >= 60 and < 80
 * - Compact banner (40 chars): Used when width >= 40 and < 60
 * - Minimal banner (24 chars): Used when width < 40
 *
 * All banners use the Network Diamond design with lightning bolt motifs.
 */
object BannerRenderer {

    /**
     * Width breakpoints for banner selection.
     */
    object Breakpoints {
        const val FULL = 80
        const val STANDARD = 60
        const val COMPACT = 40
        const val MINIMAL = COMPACT
    }

    /**
     * Banner widths for each variant.
     */
    object BannerWidths {
        const val FULL = 49
        const val STANDARD = 43
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
     * Standard banner - Network Diamond with spaced lettering.
     * Used when terminal width >= 60 and < 80 characters.
     */
    private val STANDARD_BANNER = """
               ⚡──○──⚡
            ⚡──○──⚡──○──⚡
         ⚡──○──⚡──○──⚡──○──⚡
            ⚡──○──⚡──○──⚡
               ⚡──○──⚡
              A M P E R E
    """.trimIndent()

    /**
     * Compact banner (40 chars wide) - Simplified Network Diamond.
     * Used when terminal width >= 40 and < 60 characters.
     */
    private val COMPACT_BANNER = """
      ⚡──○──⚡──○──⚡
   ⚡──○──⚡──○──⚡──○──⚡
      ⚡──○──⚡──○──⚡
        A M P E R E
    """.trimIndent()

    /**
     * Minimal banner (24 chars wide) - Single line.
     * Used when terminal width < 40 characters.
     */
    private val MINIMAL_BANNER = "⚡ AMPERE ⚡"

    /**
     * Full banner (49 chars wide) - Network Diamond with block lettering (ASCII fallback).
     */
    private val FULL_BANNER_ASCII = """
               *--o--*
            *--o--*--o--*
         *--o--*--o--*--o--*
            *--o--*--o--*
               *--o--*
        ___   __  __ ____  _____ ____  _____
       / _ \ |  \/  |  _ \| ____|  _ \| ____|
      | | | || |\/| | |_) |  _| | |_) |  _|
      | |_| || |  | |  __/| |___|  _ <| |___
       \___/ |_|  |_|_|   |_____|_| \_\_____|
              AMPERE
    """.trimIndent()

    /**
     * Standard banner - Network Diamond with spaced lettering (ASCII fallback).
     */
    private val STANDARD_BANNER_ASCII = """
               *--o--*
            *--o--*--o--*
         *--o--*--o--*--o--*
            *--o--*--o--*
               *--o--*
              A M P E R E
    """.trimIndent()

    /**
     * Compact banner (40 chars wide) - Simplified Network Diamond (ASCII fallback).
     */
    private val COMPACT_BANNER_ASCII = """
      *--o--*--o--*
   *--o--*--o--*--o--*
      *--o--*--o--*
        A M P E R E
    """.trimIndent()

    /**
     * Minimal banner (24 chars wide) - Single line (ASCII fallback).
     */
    private val MINIMAL_BANNER_ASCII = "* AMPERE *"

    /**
     * Selects and returns the appropriate banner for the given terminal width.
     *
     * @param width The terminal width in characters
     * @param supportsUnicode Whether Unicode rendering is supported
     * @return The banner string appropriate for the given width
     */
    fun selectBanner(width: Int, supportsUnicode: Boolean = true): String {
        return when {
            width >= Breakpoints.FULL -> if (supportsUnicode) FULL_BANNER else FULL_BANNER_ASCII
            width >= Breakpoints.STANDARD -> if (supportsUnicode) STANDARD_BANNER else STANDARD_BANNER_ASCII
            width >= Breakpoints.COMPACT -> if (supportsUnicode) COMPACT_BANNER else COMPACT_BANNER_ASCII
            else -> if (supportsUnicode) MINIMAL_BANNER else MINIMAL_BANNER_ASCII
        }
    }

    /**
     * Selects and returns the appropriate banner using current terminal capabilities.
     *
     * @return The banner string appropriate for the current terminal width
     */
    fun selectBanner(): String {
        val capabilities = TerminalFactory.getCapabilities()
        return selectBanner(capabilities.width, supportsUnicode = capabilities.supportsUnicode)
    }

    /**
     * Returns the full banner variant.
     */
    fun getFullBanner(): String = FULL_BANNER

    /**
     * Returns the standard banner variant.
     */
    fun getStandardBanner(): String = STANDARD_BANNER

    /**
     * Returns the compact banner variant.
     */
    fun getCompactBanner(): String = COMPACT_BANNER

    /**
     * Returns the minimal banner variant.
     */
    fun getMinimalBanner(): String = MINIMAL_BANNER

    /**
     * Returns the full banner ASCII fallback variant.
     */
    fun getFullBannerAscii(): String = FULL_BANNER_ASCII

    /**
     * Returns the standard banner ASCII fallback variant.
     */
    fun getStandardBannerAscii(): String = STANDARD_BANNER_ASCII

    /**
     * Returns the compact banner ASCII fallback variant.
     */
    fun getCompactBannerAscii(): String = COMPACT_BANNER_ASCII

    /**
     * Returns the minimal banner ASCII fallback variant.
     */
    fun getMinimalBannerAscii(): String = MINIMAL_BANNER_ASCII

    /**
     * Returns the banner variant type for the given width.
     *
     * @param width The terminal width in characters
     * @return The BannerVariant enum value
     */
    fun getBannerVariant(width: Int): BannerVariant {
        return when {
            width >= Breakpoints.FULL -> BannerVariant.FULL
            width >= Breakpoints.STANDARD -> BannerVariant.STANDARD
            width >= Breakpoints.COMPACT -> BannerVariant.COMPACT
            else -> BannerVariant.MINIMAL
        }
    }

    /**
     * Enum representing the different banner variants.
     */
    enum class BannerVariant {
        FULL,
        STANDARD,
        COMPACT,
        MINIMAL
    }
}
