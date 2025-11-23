package link.socket.ampere.util

import co.touchlab.kermit.Logger

private const val PLATFORM = "ampere"

/**
 * Creates a Logger instance with a specific tag.
 *
 * This function generates a Logger instance that includes a tag composed of the platform name
 * and the provided tag. This helps in categorizing and filtering logs based on the platform and
 * specific tags.
 *
 * @param tag The tag to be appended to the platform name for logging purposes. If null, only the platform name will be used.
 * @return A Logger instance with the specified tag.
 */
internal fun logWith(tag: String?): Logger =
    Logger.withTag("$PLATFORM/$tag")
