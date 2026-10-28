package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * The `<transport>:<requirement name>` id form is what [LinkResolutionGate]
 * matches a requirement's name against, and Socket's `NativeLinkSeeder` has
 * already written rows in it, so the form is pinned here.
 */
class LinkIdTest {

    private fun requirement(name: String, transport: Transport) =
        LinkRequirement(name = name, transport = transport, direction = LinkDirection.READ)

    private fun link(id: LinkId, transport: Transport) =
        Link(id = id, transport = transport, direction = LinkDirection.READ, egress = EgressClass.OnDevice)

    @Test
    fun `a minted id is the lowercased transport and the requirement name`() {
        assertEquals(
            LinkId("native_framework:calendar"),
            LinkId.forRequirement(requirement("calendar", Transport.NATIVE_FRAMEWORK)),
        )
        assertEquals(
            LinkId("folder_mount:folder"),
            LinkId.forRequirement(requirement("folder", Transport.FOLDER_MOUNT)),
        )
        assertEquals(
            LinkId("oauth_rest:antenna"),
            LinkId.forRequirement(requirement("antenna", Transport.OAUTH_REST)),
        )
    }

    @Test
    fun `a Link reads back the requirement name its id was minted for on every transport`() {
        Transport.entries.forEach { transport ->
            val minted = LinkId.forRequirement(requirement("vision-ocr", transport))

            assertEquals("vision-ocr", link(minted, transport).requirementName, "$transport")
        }
    }

    @Test
    fun `an opaque id names no requirement`() {
        assertNull(link(LinkId("google-oauth"), Transport.OAUTH_REST).requirementName)
        assertNull(link(LinkId("native_framework:"), Transport.NATIVE_FRAMEWORK).requirementName)
    }

    @Test
    fun `an id minted for another transport is opaque on this one`() {
        assertNull(link(LinkId("native_framework:calendar"), Transport.MCP).requirementName)
    }

    @Test
    fun `the requirement name is derived and never stored on the wire`() {
        val minted = link(LinkId("native_framework:calendar"), Transport.NATIVE_FRAMEWORK)

        val encoded = Json { encodeDefaults = true }.encodeToString(Link.serializer(), minted)

        assertFalse(encoded.contains("requirementName"), encoded)
    }
}
