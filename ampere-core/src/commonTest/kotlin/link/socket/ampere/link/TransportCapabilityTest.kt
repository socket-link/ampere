package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-platform capability table is the modelling decision AMPR-223 exists
 * to get right, so it is pinned rather than left to be rediscovered in an
 * integration test on a device.
 */
class TransportCapabilityTest {

    @Test
    fun `AppFunction is bidirectional on Android and absent everywhere else`() {
        assertEquals(
            TransportCapability.BIDIRECTIONAL,
            Transport.APP_FUNCTION.capability(PlatformTarget.ANDROID),
        )

        listOf(PlatformTarget.IOS, PlatformTarget.JVM_DESKTOP, PlatformTarget.MACOS).forEach { target ->
            assertEquals(
                TransportCapability.NONE,
                Transport.APP_FUNCTION.capability(target),
                "AppFunction must have no capability on $target",
            )
        }
    }

    @Test
    fun `iOS app-to-app runs through UriScheme rather than AppFunction`() {
        assertFalse(Transport.APP_FUNCTION.capability(PlatformTarget.IOS).canConsume)
        assertTrue(Transport.URI_SCHEME.capability(PlatformTarget.IOS).canConsume)
    }

    @Test
    fun `Ampere consumes MCP everywhere but only hosts it off-device`() {
        PlatformTarget.entries.forEach { target ->
            assertTrue(Transport.MCP.capability(target).canConsume, "MCP consume on $target")
        }

        assertFalse(Transport.MCP.capability(PlatformTarget.IOS).canProvide)
        assertFalse(Transport.MCP.capability(PlatformTarget.ANDROID).canProvide)
        assertTrue(Transport.MCP.capability(PlatformTarget.JVM_DESKTOP).canProvide)
        assertTrue(Transport.MCP.capability(PlatformTarget.MACOS).canProvide)
    }

    @Test
    fun `Ampere is never an OAuth provider`() {
        PlatformTarget.entries.forEach { target ->
            assertFalse(Transport.OAUTH_REST.capability(target).canProvide, "on $target")
        }
    }

    @Test
    fun `native frameworks are unreachable from a plain JVM`() {
        assertEquals(
            TransportCapability.NONE,
            Transport.NATIVE_FRAMEWORK.capability(PlatformTarget.JVM_DESKTOP),
        )
        assertTrue(Transport.NATIVE_FRAMEWORK.capability(PlatformTarget.IOS).canConsume)
    }

    @Test
    fun `CLI is a desktop story only`() {
        assertTrue(Transport.CLI.capability(PlatformTarget.JVM_DESKTOP).canConsume)
        assertTrue(Transport.CLI.capability(PlatformTarget.MACOS).canConsume)
        assertEquals(TransportCapability.NONE, Transport.CLI.capability(PlatformTarget.IOS))
        assertEquals(TransportCapability.NONE, Transport.CLI.capability(PlatformTarget.ANDROID))
    }

    @Test
    fun `APNS sends from the server side rather than from the handset`() {
        assertTrue(Transport.APNS.capability(PlatformTarget.JVM_DESKTOP).canConsume)
        assertEquals(TransportCapability.NONE, Transport.APNS.capability(PlatformTarget.IOS))
    }

    @Test
    fun `permits maps a role onto the capability flags`() {
        val android = Transport.APP_FUNCTION.capability(PlatformTarget.ANDROID)
        assertTrue(android.permits(TransportRole.PROVIDER))
        assertTrue(android.permits(TransportRole.CONSUMER))

        val ios = Transport.APP_FUNCTION.capability(PlatformTarget.IOS)
        assertFalse(ios.permits(TransportRole.PROVIDER))
        assertFalse(ios.permits(TransportRole.CONSUMER))
    }

    @Test
    fun `only MCP has a transport implementation today`() {
        // Cli and AppFunction are explicitly enum members with no implementation
        // (macOS post-launch; Android consumer path gated on AMPR-226). The rest
        // land with their own tickets.
        Transport.entries.forEach { transport ->
            assertEquals(
                transport == Transport.MCP,
                transport.hasImplementation,
                "${transport.name}.hasImplementation",
            )
        }
    }
}
