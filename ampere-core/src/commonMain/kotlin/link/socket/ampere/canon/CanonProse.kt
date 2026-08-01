package link.socket.ampere.canon

import kotlinx.serialization.Serializable

/**
 * A bounded window onto a canon entity's free-form text — never the entity's
 * full prose.
 *
 * [CanonTablePreview] structurally enforces the bulk rule — schema and counts
 * in canon, content by reference — for tabular content. Prose does not
 * decompose into rows and columns, so it needed its own bounded shape. Build
 * one with [bounded], which truncates to [MAX_CHARS] and records whether it
 * did.
 *
 * **Why the bound is a factory and not a `require` in `init`.** Rejecting an
 * oversized value at *decode* time would make an already-recorded trace
 * permanently undecodable, which is precisely the failure the `@SerialName`
 * stability invariant exists to prevent. A canon type must always decode;
 * bounding is a write-side concern. [isWithinBounds] exposes the rule so it is
 * testable rather than merely stated.
 *
 * @property text The prose, already truncated to [MAX_CHARS].
 * @property truncated True when [bounded] cut [text] short. Unlike
 *   [CanonTablePreview], there is no sibling count field a reader can fall
 *   back on to learn the untruncated length — a `true` value means the rest of
 *   the prose is simply gone from this projection.
 */
@Serializable
data class CanonProse(
    val text: String = "",
    val truncated: Boolean = false,
) {

    /** Whether this value honours the [bounded] limit. */
    val isWithinBounds: Boolean
        get() = text.length <= MAX_CHARS

    companion object {

        /**
         * Long enough for a document snippet — several paragraphs, not one —
         * while a worst-case value still clears the 32 KiB projection budget
         * asserted in `CanonWorkEntitiesTest` with room to spare for the rest
         * of the entity and JSON overhead.
         */
        const val MAX_CHARS: Int = 8_000

        /**
         * Truncate [text] to [MAX_CHARS], flagging whether anything was cut.
         *
         * The only construction path adapters should use. Worst case is
         * [MAX_CHARS] UTF-16 units of content. In bytes that peaks at ×3, not
         * ×4 — a four-byte codepoint costs two units, so the worst
         * byte-per-unit ratio belongs to three-byte BMP characters like
         * CJK — giving 24,000 bytes, comfortably inside the budget asserted in
         * `CanonWorkEntitiesTest`.
         */
        fun bounded(text: String): CanonProse {
            val truncated = text.length > MAX_CHARS
            return CanonProse(text = text.takeProse(), truncated = truncated)
        }

        /**
         * [MAX_CHARS] units, without splitting a surrogate pair.
         *
         * A naive `take` can leave a trailing lone high surrogate, which is
         * not valid UTF-8 — it encodes as a replacement character, so the
         * value would fail to round-trip through the very serialization these
         * bounds exist to protect.
         */
        private fun String.takeProse(): String = when {
            length <= MAX_CHARS -> this
            this[MAX_CHARS - 1].isHighSurrogate() -> substring(0, MAX_CHARS - 1)
            else -> substring(0, MAX_CHARS)
        }
    }
}
