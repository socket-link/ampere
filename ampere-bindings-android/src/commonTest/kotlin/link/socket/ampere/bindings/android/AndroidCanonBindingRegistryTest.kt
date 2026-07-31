package link.socket.ampere.bindings.android

import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.ampere.canon.CanonType

class AndroidCanonBindingRegistryTest {

    @Test
    fun `Android bindings are all still marked unverified`() {
        // androidx.appfunctions is not on this repo's classpath, so
        // AppFunctionSchemaDefinition could not be enumerated during recon.
        // When it lands, this test flips to assert real bindings.
        CanonType.entries.forEach { type ->
            val schema = AndroidCanonBindingRegistry.bindingFor(type).schema
            assertTrue(
                schema is AndroidSchemaBinding.PendingSdkVerification || schema is AndroidSchemaBinding.None,
                "${type.wireName} claims a verified Android binding without an SDK pass",
            )
        }
    }
}
