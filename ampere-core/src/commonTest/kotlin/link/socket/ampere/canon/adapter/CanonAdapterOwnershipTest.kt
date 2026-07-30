package link.socket.ampere.canon.adapter

import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.ampere.canon.CanonType

/**
 * `CanonBinding.lossyFields` (what a projection drops) and `WritableCanonAdapter.ownedFields`
 * (what it writes back) are declared in two different files with nothing tying them together.
 * A field cannot honestly be both: `ownedFields` names what the canon entity captures and can
 * write, `lossyFields` names what it never captured in the first place. An adapter that owns a
 * field its own canon type calls lossy is contradicting its binding's documentation of itself.
 */
class CanonAdapterOwnershipTest {

    @Test
    fun `a reference adapter never owns a field its canon type declares lossy`() {
        val adapters: List<Pair<CanonType, Set<String>>> = listOf(
            CanonType.EMAIL_MESSAGE to MailMessageAdapter(FakeNativeStore()).ownedFields,
            CanonType.DOCUMENT to FileDocumentAdapter(FakeNativeStore()).ownedFields,
        )

        adapters.forEach { (canonType, ownedFields) ->
            val overlap = ownedFields intersect canonType.binding.lossyFields.toSet()
            assertTrue(
                overlap.isEmpty(),
                "${canonType.wireName} declares $overlap as both owned (written) and lossy " +
                    "(dropped) — the projection and the binding disagree about what this " +
                    "adapter captures",
            )
        }
    }

    @Test
    fun `an adapter that overreaches ownedFields writes a field its canon type already calls lossy`() {
        // Ties the two guards together: OverreachingMailAdapter's stray write (providerLabels) is
        // not an arbitrary string picked for the test — it is a field EMAIL_MESSAGE's binding
        // already documents as dropped by the projection, i.e. exactly the kind of field the
        // ownership check above exists to keep out of ownedFields.
        assertTrue("providerLabels" in CanonType.EMAIL_MESSAGE.binding.lossyFields)
    }
}
