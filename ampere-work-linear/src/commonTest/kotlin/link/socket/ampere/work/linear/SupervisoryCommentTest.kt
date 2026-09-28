package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The claim and escalation formats are a wire protocol between two supervisor
 * processes, so these tests are about *exactness*: every constructible value
 * renders to text that parses back to the same value, and nothing else parses to
 * anything at all.
 */
class SupervisoryCommentTest {

    @Test
    fun `a claim renders to the ratified format`() {
        val claim = SupervisoryComment.Claim("AMPR-305", SupervisorInstanceId("supervisor-7f3a"))

        assertEquals("claim:AMPR-305:supervisor-7f3a", claim.render())
    }

    @Test
    fun `an escalation renders its header then a blank line then the body`() {
        val escalation = SupervisoryComment.Escalation(
            issue = "AMPR-305",
            instanceId = SupervisorInstanceId("supervisor-7f3a"),
            body = "The verification gate needs a decision a machine must not make.",
        )

        assertEquals(
            "esc:AMPR-305:supervisor-7f3a\n\n" +
                "The verification gate needs a decision a machine must not make.",
            escalation.render(),
        )
    }

    @Test
    fun `an escalation with no body renders the header alone`() {
        val escalation = SupervisoryComment.Escalation("AMPR-1", SupervisorInstanceId("a"))

        assertEquals("esc:AMPR-1:a", escalation.render())
    }

    @Test
    fun `every form round-trips through render and parse`() {
        val comments = listOf(
            SupervisoryComment.Claim("AMPR-305", SupervisorInstanceId("supervisor-7f3a")),
            SupervisoryComment.Claim("AMPR-1", SupervisorInstanceId("a")),
            SupervisoryComment.Escalation("AMPR-305", SupervisorInstanceId("b")),
            SupervisoryComment.Escalation("AMPR-305", SupervisorInstanceId("b"), "one line"),
            // A body is arbitrary markdown, and markdown round-trips
            // byte-identically through this work source. Headings, fences,
            // blank lines and unicode all have to survive the header split.
            SupervisoryComment.Escalation(
                issue = "AMPR-306",
                instanceId = SupervisorInstanceId("c"),
                body = "## Why\n\n- a table follows\n\n| x | y |\n| - | - |\n| 1 | 2 |\n\n```\ncode\n```\n⚡",
            ),
            // Leading and trailing newlines in the body are the case a naive
            // trim-then-split loses.
            SupervisoryComment.Escalation("AMPR-307", SupervisorInstanceId("d"), "\n\nindented start"),
            SupervisoryComment.Escalation("AMPR-308", SupervisorInstanceId("e"), "trailing\n\n"),
        )

        comments.forEach { comment ->
            assertEquals(
                comment,
                SupervisoryComment.parse(comment.render()),
                "round-trip failed for ${comment.render()}",
            )
        }
    }

    @Test
    fun `anything that is not a supervisory comment parses to null`() {
        val bodies = listOf(
            "",
            "Looks good to me",
            "This comment thread is synced to a corresponding GitHub issue.",
            "## Recon findings\n\nSome prose.",
            // Right prefix, wrong field count.
            "claim:AMPR-305",
            "claim:AMPR-305:instance:extra",
            "esc:AMPR-305",
            // Right shape, blank fields.
            "claim::instance",
            "claim:AMPR-305:",
            // An unknown prefix must not be treated as a claim.
            "claimed:AMPR-305:instance",
            "release:AMPR-305:instance",
            // A header with a newline in it is prose, not a header.
            "claim:AMPR-305:instance\nand more",
        )

        bodies.forEach { body ->
            assertNull(SupervisoryComment.parse(body), "expected null for ${body.take(40)}")
        }
    }

    @Test
    fun `a claim with a body attached is not a claim`() {
        // A claim carries no body, so text after the header means this is
        // something else that merely starts like a claim — arbitrating on it
        // would put a guessed value into a race.
        assertNull(SupervisoryComment.parse("claim:AMPR-305:instance\n\nwhy I took it"))
    }

    @Test
    fun `an instance id carrying the separator is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { SupervisorInstanceId("host:1234") }
        assertFailsWith<IllegalArgumentException> { SupervisorInstanceId("") }
        assertFailsWith<IllegalArgumentException> { SupervisorInstanceId("two\nlines") }
    }

    @Test
    fun `an issue carrying the separator is rejected at construction`() {
        val instance = SupervisorInstanceId("a")

        assertFailsWith<IllegalArgumentException> { SupervisoryComment.Claim("AMPR:305", instance) }
        assertFailsWith<IllegalArgumentException> { SupervisoryComment.Claim(" ", instance) }
        assertFailsWith<IllegalArgumentException> {
            SupervisoryComment.Escalation("AMPR\n305", instance)
        }
    }

    @Test
    fun `the prefixes are the ratified literals`() {
        // Named constants, and these are the names. A rename makes every
        // in-flight claim invisible to the process that has to arbitrate it.
        assertEquals("claim:", SupervisoryComment.CLAIM_PREFIX)
        assertEquals("esc:", SupervisoryComment.ESCALATION_PREFIX)
    }
}
