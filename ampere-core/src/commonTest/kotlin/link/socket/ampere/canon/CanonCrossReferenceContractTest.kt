package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import link.socket.ampere.link.LinkId

/**
 * Pins the caller contract settled by AMPR-266 for every nullable `CanonId`
 * cross-reference — [CanonEmailMessage.mailboxId], [CanonWorkItem.projectId],
 * [CanonMilestone.projectId], [CanonTable.documentId] — rather than a resolver
 * mechanism, which the ticket costed and rejected. See the invariant in
 * `docs/concepts/domain-canon.md`.
 */
class CanonCrossReferenceContractTest {

    private fun provenanceFrom(linkId: LinkId, nativeId: String) = CanonProvenance(
        sourceHandle = SourceHandle(linkId = linkId, sourceSystem = "mcp:linear", nativeId = nativeId),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    /** Resolves [reference] against [candidates], scoped to [linkId] — the contract-following way. */
    private fun resolveWithinLink(
        reference: CanonId,
        linkId: LinkId,
        candidates: List<CanonMailbox>,
    ): CanonMailbox? = candidates
        .filter { it.provenance.sourceHandle.linkId == linkId }
        .find { it.canonId == reference }

    /** Resolves [reference] by [CanonId] equality alone, ignoring Link scope — the unsafe way. */
    private fun resolveIgnoringLink(
        reference: CanonId,
        candidates: List<CanonMailbox>,
    ): CanonMailbox? = candidates.find { it.canonId == reference }

    @Test
    fun `null does not distinguish unattached from unreported`() {
        val unattached = CanonEmailMessage(
            canonId = CanonId("m-1"),
            provenance = provenanceFrom(LinkId("link-1"), "msg-1"),
            subject = "No mailbox, by design",
            from = null,
            mailboxId = null,
        )
        val unreported = CanonEmailMessage(
            canonId = CanonId("m-2"),
            provenance = provenanceFrom(LinkId("link-1"), "msg-2"),
            subject = "Provider never said",
            from = null,
            mailboxId = null,
        )

        // Both collapse to the same null — the type has no way to tell them
        // apart, which is exactly the ambiguity the KDoc documents.
        assertNull(unattached.mailboxId)
        assertNull(unreported.mailboxId)
        assertEquals(unattached.mailboxId, unreported.mailboxId)
    }

    @Test
    fun `resolving a reference by CanonId alone can match the wrong Link`() {
        val referencingLink = LinkId("link-linear")
        val otherLink = LinkId("link-jira")

        // Two Links independently minted the same CanonId string for two
        // different mailboxes — CanonId is Ampere-scoped, not globally unique.
        val wrongMailbox = CanonMailbox(
            canonId = CanonId("mb-1"),
            provenance = provenanceFrom(otherLink, "INBOX"),
            name = "Jira notifications",
        )
        val rightMailbox = CanonMailbox(
            canonId = CanonId("mb-1"),
            provenance = provenanceFrom(referencingLink, "mailbox-42"),
            name = "Support",
        )
        val candidates = listOf(wrongMailbox, rightMailbox)

        val reference = CanonId("mb-1")

        // Matching on CanonId alone finds *a* mailbox, but not necessarily the
        // one the reference actually named.
        val unsafe = resolveIgnoringLink(reference, listOf(wrongMailbox))
        assertEquals(wrongMailbox, unsafe)

        // Scoping to the same Link the reference travelled over is what makes
        // resolution correct.
        val safe = resolveWithinLink(reference, referencingLink, candidates)
        assertEquals(rightMailbox, safe)
    }

    @Test
    fun `nothing guarantees the referenced entity was ever perceived`() {
        val referencingLink = LinkId("link-linear")
        val reference = CanonId("mb-missing")

        val resolved = resolveWithinLink(reference, referencingLink, candidates = emptyList())

        assertNull(resolved)
    }
}
