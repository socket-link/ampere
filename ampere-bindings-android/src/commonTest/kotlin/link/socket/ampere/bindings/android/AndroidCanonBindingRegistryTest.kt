package link.socket.ampere.bindings.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.ampere.canon.CanonType

class AndroidCanonBindingRegistryTest {

    @Test
    fun `the bindings name the artifact they were enumerated from`() {
        // Moving this is the signal to re-run the enumeration in the AMPR-222
        // document's Appendix, not just to edit the string.
        assertEquals(
            "androidx.appfunctions:appfunctions:1.0.0-beta01",
            AndroidCanonBindingRegistry.VERIFIED_AGAINST,
        )
    }

    @Test
    fun `no canon type is left unverified`() {
        CanonType.entries.forEach { type ->
            assertTrue(
                AndroidCanonBindingRegistry.bindingFor(type).schema != AndroidSchemaBinding.PendingSdkVerification,
                "${type.wireName} is still pending an AppFunctions SDK pass",
            )
        }
    }

    @Test
    fun `beta01 ships no predefined schema so every canon type binds to None`() {
        // androidx.appfunctions 1.0.0-beta01 declares @AppFunctionSchemaDefinition
        // but no interface in appfunctions, appfunctions-compiler or
        // appfunctions-testing carries it, and no appfunctions-schema artifact is
        // published. A Predefined entry here would name a schema that does not ship.
        CanonType.entries.forEach { type ->
            val binding = AndroidCanonBindingRegistry.bindingFor(type)
            assertEquals(
                AndroidSchemaBinding.None,
                binding.schema,
                "${type.wireName} claims an Android schema that beta01 does not ship",
            )
            assertEquals(
                emptyList<String>(),
                binding.lossyFields,
                "${type.wireName} has no Android projection, so it has no projection to lose fields in",
            )
        }
    }
}
