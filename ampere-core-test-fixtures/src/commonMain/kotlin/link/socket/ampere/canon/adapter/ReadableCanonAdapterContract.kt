package link.socket.ampere.canon.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

/**
 * Structural test suite every [ReadableCanonAdapter] must satisfy.
 *
 * A subclass proves nothing about its own business logic here — only that it
 * honours the SPI's non-negotiables: schema-checked projection, mandatory
 * provenance, and typed (never thrown) failures. [WritableCanonAdapterContract]
 * adds the write-back half on top of this.
 */
abstract class ReadableCanonAdapterContract<E : CanonEntity> {

    /** The adapter under test, backed by [store]. */
    protected abstract fun adapter(store: FakeNativeStore): ReadableCanonAdapter<E>

    /** A native payload this adapter's [ReadableCanonAdapter.nativeSchema] accepts. */
    protected abstract fun fixturePayload(): NativePayload

    /** A field name [fixturePayload] must carry that the canon type requires. */
    protected abstract val requiredField: String

    protected val handle: SourceHandle = SourceHandle(
        linkId = LinkId("contract-link"),
        sourceSystem = "contract-fixture",
        nativeId = "contract-native-id",
    )

    protected val observedAt: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)

    protected fun failureOf(result: Result<*>): CanonConversionFailure {
        val error = result.exceptionOrNull()
        assertIs<CanonConversionException>(error)
        return error.failure
    }

    @Test
    fun `projection wires provenance and cannot omit it`() {
        val underTest = adapter(FakeNativeStore())
        val payload = fixturePayload()

        val entity = underTest.project(payload, handle, observedAt).getOrThrow()

        assertEquals(handle, entity.provenance.sourceHandle)
        assertEquals(observedAt, entity.provenance.observedAt)
        assertEquals(payload, entity.provenance.nativePayload)
    }

    @Test
    fun `carryNativePayload off drops the payload but keeps provenance`() {
        val underTest = adapter(FakeNativeStore())

        val entity = underTest
            .project(fixturePayload(), handle, observedAt, carryNativePayload = false)
            .getOrThrow()

        assertNull(entity.provenance.nativePayload)
        assertEquals(handle, entity.provenance.sourceHandle)
    }

    @Test
    fun `projecting the wrong native schema fails rather than guessing`() {
        val underTest = adapter(FakeNativeStore())
        val wrongSchema = NativeSchema("__contract_wrong_schema__")
        val wrongShape = NativePayload(wrongSchema, JsonObject(emptyMap()))

        val failure = failureOf(underTest.project(wrongShape, handle, observedAt))

        assertIs<CanonConversionFailure.SchemaMismatch>(failure)
        assertEquals(underTest.nativeSchema, failure.expectedSchema)
        assertEquals(wrongSchema, failure.actualSchema)
    }

    @Test
    fun `a missing required field is a typed failure and never a throw`() {
        val underTest = adapter(FakeNativeStore())
        val payload = fixturePayload()
        val withoutRequired = NativePayload(
            schema = payload.schema,
            fields = JsonObject(payload.fields.filterKeys { it != requiredField }),
        )

        val failure = failureOf(underTest.project(withoutRequired, handle, observedAt))

        assertIs<CanonConversionFailure.MissingRequiredField>(failure)
        assertEquals(requiredField, failure.field)
    }
}
