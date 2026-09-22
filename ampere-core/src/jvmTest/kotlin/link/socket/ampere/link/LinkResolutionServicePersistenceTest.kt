package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.LinkEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.canon.CanonType
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest

/**
 * The with-door half of the link lifecycle tests (F1, AMPR-339): every announcement the
 * service makes is persisted to the `EventStore` before it reaches the bus, so the store
 * and a subscriber see the same event. Bodies use `runBlocking`: the door persists on a real
 * IO dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkResolutionServicePersistenceTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())

    private val googleLink = Link(
        id = LinkId("google-oauth"),
        transport = Transport.OAUTH_REST,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.ThirdParty("google"),
        scope = setOf(CanonType.CALENDAR_EVENT, CanonType.EMAIL_MESSAGE),
        credentialRef = CredentialRef("keychain://google"),
    )

    private val calendarRequirement = LinkRequirement(
        name = "calendar",
        transport = Transport.OAUTH_REST,
        direction = LinkDirection.READ_WRITE,
        minimumScope = setOf(CanonType.CALENDAR_EVENT),
    )

    private fun manifest(vararg requirements: LinkRequirement) = PlugManifest(
        id = PlugId("calendar-plug"),
        name = "Calendar Plug",
        version = "1.0.0",
        requiredLinks = requirements.toList(),
        consumes = setOf(CanonType.CALENDAR_EVENT),
        emits = setOf(CanonType.CALENDAR_EVENT),
    )

    private fun door(): InMemoryEventApi.Handle =
        InMemoryEventApi.open(agentId = "link-resolution", scope = doorScope)

    private fun service(store: LinkStore, door: InMemoryEventApi.Handle) =
        LinkResolutionService(linkStore = store, platform = PlatformTarget.ANDROID, eventApi = door.api)

    private inline fun <reified E : LinkEvent> InMemoryEventApi.Handle.collect(
        eventType: String,
        into: MutableList<E>,
    ) {
        bus.subscribe<E, EventSubscription.ByEventClassType>(
            agentId = "observer",
            eventType = eventType,
        ) { event, _ -> into += event }
    }

    @Test
    fun `a successful resolution is persisted and announced`() = runBlocking {
        door().use { door ->
            val received = mutableListOf<LinkEvent.LinkResolved>()
            door.collect(LinkEvent.LinkResolved.EVENT_TYPE, received)

            val store = InMemoryLinkStore(listOf(googleLink))
            store.grant(PlugId("calendar-plug"), googleLink.id, Instant.fromEpochMilliseconds(1))

            service(store, door)
                .resolve(PlugId("calendar-plug"), manifest(calendarRequirement), agentId = "agent-planner")
                .getOrThrow()

            val stored = door.repository.getEventsByType(LinkEvent.LinkResolved.EVENT_TYPE).getOrThrow()
            val seen = assertIs<LinkEvent.LinkResolved>(stored.single())
            assertEquals("calendar", seen.requirementName)
            assertEquals(googleLink.id, seen.linkId)
            assertEquals(Transport.OAUTH_REST, seen.transport)
            assertEquals(EventSource.Agent("agent-planner"), seen.eventSource)
            assertEquals(listOf(seen.eventId), received.map { it.eventId })
        }
    }

    @Test
    fun `a failed resolution is never silent`() = runBlocking {
        door().use { door ->
            service(InMemoryLinkStore(), door)
                .resolve(PlugId("calendar-plug"), manifest(calendarRequirement))

            val stored = door.repository.getEventsByType(LinkEvent.LinkResolutionFailed.EVENT_TYPE).getOrThrow()
            val seen = assertIs<LinkEvent.LinkResolutionFailed>(stored.single())
            assertIs<LinkResolutionFailure.MissingLink>(seen.failure)
            assertEquals(LinkEvent.LinkResolutionFailed.NO_LINK, seen.linkId)
        }
    }

    @Test
    fun `an ungranted Link is announced with the id consent would be asked on`() = runBlocking {
        door().use { door ->
            service(InMemoryLinkStore(listOf(googleLink)), door)
                .resolve(PlugId("stranger-plug"), manifest(calendarRequirement))

            val stored = door.repository.getEventsByType(LinkEvent.LinkResolutionFailed.EVENT_TYPE).getOrThrow()
            val seen = assertIs<LinkEvent.LinkResolutionFailed>(stored.single())
            val failure = assertIs<LinkResolutionFailure.UngrantedLink>(seen.failure)
            assertEquals(googleLink.id, failure.linkId)
            assertEquals(googleLink.id, seen.linkId)
        }
    }

    @Test
    fun `a grant and a revocation persist their lifecycle`() = runBlocking {
        door().use { door ->
            val store = InMemoryLinkStore(listOf(googleLink))
            val service = service(store, door)

            service.grant(PlugId("calendar-plug"), googleLink.id).getOrThrow()
            service.grant(PlugId("gmail-plug"), googleLink.id).getOrThrow()
            service.revokeLink(googleLink.id).getOrThrow()

            val granted = door.repository.getEventsByType(LinkEvent.LinkGranted.EVENT_TYPE).getOrThrow()
            assertEquals(
                setOf("calendar-plug", "gmail-plug"),
                granted.map { (it as LinkEvent.LinkGranted).plugId }.toSet(),
            )

            val revoked = door.repository.getEventsByType(LinkEvent.LinkRevoked.EVENT_TYPE).getOrThrow()
            val seen = assertIs<LinkEvent.LinkRevoked>(revoked.single())
            assertEquals(RevocationScope.LINK, seen.scope)
            assertEquals(setOf("calendar-plug", "gmail-plug"), seen.affectedPlugIds.toSet())
        }
    }
}
