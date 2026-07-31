package link.socket.ampere.canon.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import link.socket.ampere.canon.CanonEntity

/**
 * Adds the write-back half of the structural suite on top of
 * [ReadableCanonAdapterContract].
 *
 * [unprojectedField] is the safeguard: it forces a subclass to name a field
 * its own projection never reads, so [fixturePayload] can't be a fixture with
 * nothing left to preserve and the merge test can't trivially pass.
 */
abstract class WritableCanonAdapterContract<E : CanonEntity> : ReadableCanonAdapterContract<E>() {

    abstract override fun adapter(store: FakeNativeStore): WritableCanonAdapter<E>

    /** A field name in [fixturePayload] that this adapter's projection does not read. */
    protected abstract val unprojectedField: String

    @Test
    fun `unowned native fields survive a merge`() = runTest {
        val payload = fixturePayload()
        val store = FakeNativeStore(mapOf(handle.nativeId to payload))
        val underTest = adapter(store)

        val entity = underTest.project(payload, handle, observedAt).getOrThrow()
        val merged = underTest.mergeForWriteBack(entity).getOrThrow()

        assertEquals(payload.fields[unprojectedField], merged.fields[unprojectedField])
    }

    @Test
    fun `round-trip is byte-identical when nothing changed`() = runTest {
        val payload = fixturePayload()
        val underTest = adapter(FakeNativeStore())

        val entity = underTest.project(payload, handle, observedAt).getOrThrow()
        val merged = underTest.mergeForWriteBack(entity).getOrThrow()

        assertEquals(payload, merged)
    }

    @Test
    fun `a failed re-fetch surfaces as SourceUnavailable never a throw`() = runTest {
        val payload = fixturePayload()
        val store = FakeNativeStore(mapOf(handle.nativeId to payload))
        val underTest = adapter(store)

        val entity = underTest
            .project(payload, handle, observedAt, carryNativePayload = false)
            .getOrThrow()

        store.failNextFetch = "contract-induced failure"
        val failure = failureOf(underTest.mergeForWriteBack(entity))

        assertIs<CanonConversionFailure.SourceUnavailable>(failure)
        assertEquals(handle.nativeId, failure.nativeId)
    }
}
