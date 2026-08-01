package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.EventRegistry
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.LinkEvent
import link.socket.ampere.canon.CanonType

/**
 * Links persist as JSON in `Links.link_json` and their lifecycle events land in
 * traces. Both are wire contracts, so the discriminators are pinned.
 */
class LinkSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val link = Link(
        id = LinkId("google-oauth"),
        transport = Transport.OAUTH_REST,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.ThirdParty("google"),
        scope = setOf(CanonType.CALENDAR_EVENT, CanonType.EMAIL_MESSAGE),
        credentialRef = CredentialRef("keychain://google"),
    )

    @Test
    fun `a Link round-trips with its scope and egress intact`() {
        val decoded = json.decodeFromString(
            Link.serializer(),
            json.encodeToString(Link.serializer(), link),
        )

        assertEquals(link, decoded)
    }

    @Test
    fun `every egress class round-trips`() {
        listOf(
            EgressClass.OnDevice,
            EgressClass.FirstParty,
            EgressClass.ThirdParty("uber"),
        ).forEach { egress ->
            val encoded = json.encodeToString(EgressClass.serializer(), egress)
            assertEquals(egress, json.decodeFromString(EgressClass.serializer(), encoded))
        }
    }

    @Test
    fun `egress discriminators are pinned`() {
        assertTrue(
            json.encodeToString(EgressClass.serializer(), EgressClass.OnDevice)
                .contains("\"type\":\"egress.on_device\""),
        )
        assertTrue(
            json.encodeToString(EgressClass.serializer(), EgressClass.ThirdParty("uber"))
                .contains("\"type\":\"egress.third_party\""),
        )
    }

    @Test
    fun `a credential ref never carries the credential`() {
        val encoded = json.encodeToString(CredentialRef.serializer(), link.credentialRef!!)

        // The alias is a pointer; if a token ever appears in this type, every
        // trace that recorded a Link becomes a breach.
        assertTrue(encoded.contains("keychain://google"))
        assertEquals(
            setOf("keychainAlias", "revokedAt"),
            Regex("\"(\\w+)\":").findAll(encoded).map { it.groupValues[1] }.toSet(),
        )
    }

    @Test
    fun `a folder ref never carries the bookmark bytes`() {
        val folderRef = FolderRef("mount-1")
        val encoded = json.encodeToString(FolderRef.serializer(), folderRef)

        // The mount id is a pointer; if bookmark bytes ever appear in this
        // type, every trace that recorded a Link becomes a breach.
        assertTrue(encoded.contains("mount-1"))
        assertEquals(
            setOf("mountId", "revokedAt"),
            Regex("\"(\\w+)\":").findAll(encoded).map { it.groupValues[1] }.toSet(),
        )
    }

    @Test
    fun `a Link round-trips with its folder ref intact`() {
        val withFolder = link.copy(folderRef = FolderRef("mount-1"))
        val decoded = json.decodeFromString(
            Link.serializer(),
            json.encodeToString(Link.serializer(), withFolder),
        )

        assertEquals(withFolder, decoded)
    }

    @Test
    fun `every resolution failure round-trips`() {
        val failures = listOf<LinkResolutionFailure>(
            LinkResolutionFailure.MissingLink("calendar", Transport.MCP, LinkDirection.READ),
            LinkResolutionFailure.DirectionViolation(
                "notify",
                LinkId("apns"),
                LinkDirection.READ,
                LinkDirection.WRITE,
            ),
            LinkResolutionFailure.ScopeViolation(
                "calendar",
                LinkId("google-oauth"),
                setOf(CanonType.HEALTH_SAMPLE),
            ),
            LinkResolutionFailure.RevokedCredential(
                "calendar",
                LinkId("google-oauth"),
                RevocationScope.PLUG_GRANT,
            ),
            LinkResolutionFailure.RevokedCredential(
                "files",
                LinkId("folder-mount"),
                RevocationScope.FOLDER,
            ),
            LinkResolutionFailure.TransportUnsupported(
                "calendar",
                LinkId("app-function"),
                Transport.APP_FUNCTION,
                PlatformTarget.IOS,
                TransportRole.CONSUMER,
            ),
        )

        failures.forEach { failure ->
            val encoded = json.encodeToString(LinkResolutionFailure.serializer(), failure)
            assertEquals(
                failure,
                json.decodeFromString(LinkResolutionFailure.serializer(), encoded),
            )
        }
    }

    @Test
    fun `every LinkEvent variant is registered on the bus`() {
        listOf(
            LinkEvent.LinkGranted.EVENT_TYPE,
            LinkEvent.LinkRevoked.EVENT_TYPE,
            LinkEvent.LinkResolved.EVENT_TYPE,
            LinkEvent.LinkResolutionFailed.EVENT_TYPE,
        ).forEach { eventType ->
            assertTrue(
                eventType in EventRegistry.allEventTypes,
                "$eventType is missing from EventRegistry — trace capture would never see it",
            )
        }
    }

    @Test
    fun `LinkEvent variants round-trip through the sealed Event serializer`() {
        val events = mapOf(
            "LinkEvent.LinkGranted" to LinkEvent.LinkGranted(
                eventId = "e1",
                timestamp = now,
                eventSource = EventSource.Human,
                linkId = link.id,
                plugId = "calendar-plug",
                transport = Transport.OAUTH_REST,
            ),
            "LinkEvent.LinkRevoked" to LinkEvent.LinkRevoked(
                eventId = "e2",
                timestamp = now,
                eventSource = EventSource.Human,
                linkId = link.id,
                scope = RevocationScope.LINK,
                affectedPlugIds = listOf("calendar-plug", "gmail-plug"),
            ),
            "LinkEvent.LinkResolved" to LinkEvent.LinkResolved(
                eventId = "e3",
                timestamp = now,
                eventSource = EventSource.Agent("agent-1"),
                linkId = link.id,
                plugId = "calendar-plug",
                requirementName = "calendar",
                transport = Transport.OAUTH_REST,
            ),
            "LinkEvent.LinkResolutionFailed" to LinkEvent.LinkResolutionFailed(
                eventId = "e4",
                timestamp = now,
                eventSource = EventSource.Agent("agent-1"),
                linkId = LinkEvent.LinkResolutionFailed.NO_LINK,
                plugId = "calendar-plug",
                failure = LinkResolutionFailure.MissingLink(
                    "calendar",
                    Transport.MCP,
                    LinkDirection.READ,
                ),
            ),
        )

        events.forEach { (discriminator, event) ->
            val encoded = json.encodeToString(LinkEvent.serializer(), event)
            assertTrue(encoded.contains("\"type\":\"$discriminator\""), encoded)
            assertEquals(event, json.decodeFromString(LinkEvent.serializer(), encoded))
        }
    }

    @Test
    fun `summaries name the plug and the link and the reason`() {
        val failed = LinkEvent.LinkResolutionFailed(
            eventId = "e4",
            timestamp = now,
            eventSource = EventSource.Agent("agent-1"),
            linkId = LinkEvent.LinkResolutionFailed.NO_LINK,
            plugId = "calendar-plug",
            failure = LinkResolutionFailure.MissingLink("calendar", Transport.MCP, LinkDirection.READ),
        )

        val summary = failed.getSummary({ "[urgency]" }, { "agent-1" })

        assertTrue(summary.contains("calendar-plug"))
        assertTrue(summary.contains("calendar"))
        assertTrue(summary.contains("MissingLink"))
    }
}
