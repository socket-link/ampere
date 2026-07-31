package link.socket.ampere.plug

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Identifier for a [PlugManifest].
 *
 * Constrained to the same grammar as Socket's `ComponentId`
 * (`[a-z0-9_-]+`) so a Plug id is always addressable by the consent
 * ledger, the marketplace, and the registry on the other side of the
 * wire. A shipped Socket defect traced back to a Plug id containing
 * dots that could never be a valid `ComponentId` — validating here
 * turns that into a construction-time failure instead of a silent,
 * late one.
 */
@JvmInline
@Serializable
value class PlugId(val value: String) {

    init {
        require(value.matches(VALID_PATTERN)) {
            "PlugId must match $VALID_PATTERN, was: \"$value\""
        }
    }

    companion object {
        private val VALID_PATTERN = Regex("^[a-z0-9_-]+$")
    }
}
