package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.LinkEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.canon.CanonType
import link.socket.ampere.plug.PlugManifest

class LinkResolutionServiceTest {

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
        id = "calendar-plug",
        name = "Calendar Plug",
        version = "1.0.0",
        requiredLinks = requirements.toList(),
        consumes = setOf(CanonType.CALENDAR_EVENT),
        emits = setOf(CanonType.CALENDAR_EVENT),
    )

    private fun service(
        store: LinkStore,
        platform: PlatformTarget = PlatformTarget.ANDROID,
        bus: EventSerialBus? = null,
    ) = LinkResolutionService(linkStore = store, platform = platform, eventBus = bus)

    @Test
    fun `resolution returns the Link keyed by requirement name`() = runTest {
        val store = InMemoryLinkStore(listOf(googleLink))
        store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))

        val resolved = service(store)
            .resolve("calendar-plug", manifest(calendarRequirement))
            .getOrThrow()

        assertEquals(googleLink, resolved["calendar"])
        assertNull(resolved["nonexistent"])
    }

    @Test
    fun `a missing Link is a Result failure and never a throw`() = runTest {
        val store = InMemoryLinkStore()

        val result = service(store).resolve("calendar-plug", manifest(calendarRequirement))

        assertTrue(result.isFailure)
        val error = assertIs<LinkResolutionException>(result.exceptionOrNull())
        assertIs<LinkResolutionFailure.MissingLink>(error.failures.single())
    }

    @Test
    fun `every failure is reported rather than just the first`() = runTest {
        val store = InMemoryLinkStore(listOf(googleLink))
        store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))

        val result = service(store).resolve(
            plugId = "calendar-plug",
            manifest = manifest(
                calendarRequirement.copy(
                    name = "health",
                    minimumScope = setOf(CanonType.HEALTH_SAMPLE),
                ),
                LinkRequirement(
                    name = "notify",
                    transport = Transport.APNS,
                    direction = LinkDirection.WRITE,
                    minimumScope = setOf(CanonType.MESSAGE),
                ),
            ),
        )

        val error = assertIs<LinkResolutionException>(result.exceptionOrNull())
        assertEquals(2, error.failures.size)
        assertContentEquals(
            listOf("health", "notify"),
            error.failures.map { it.requirementName },
        )
    }

    @Test
    fun `an optional requirement does not fail the whole resolution`() = runTest {
        val store = InMemoryLinkStore(listOf(googleLink))
        store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))

        val resolved = service(store).resolve(
            plugId = "calendar-plug",
            manifest = manifest(
                calendarRequirement,
                LinkRequirement(
                    name = "notify",
                    transport = Transport.APNS,
                    direction = LinkDirection.WRITE,
                    minimumScope = setOf(CanonType.MESSAGE),
                    optional = true,
                ),
            ),
        ).getOrThrow()

        assertNotNull(resolved["calendar"])
        assertNull(resolved["notify"])
    }

    @Test
    fun `an optional requirement whose Link is misconfigured still does not block`() = runTest {
        val writeOnly = Link(
            id = LinkId("apns"),
            transport = Transport.APNS,
            direction = LinkDirection.WRITE,
            egress = EgressClass.FirstParty,
            scope = setOf(CanonType.MESSAGE),
        )
        val store = InMemoryLinkStore(listOf(googleLink, writeOnly))
        store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))
        store.grant("calendar-plug", writeOnly.id, Instant.fromEpochMilliseconds(2))

        val resolved = service(store, platform = PlatformTarget.JVM_DESKTOP).resolve(
            plugId = "calendar-plug",
            manifest = manifest(
                calendarRequirement,
                LinkRequirement(
                    name = "notify",
                    transport = Transport.APNS,
                    // Asking to read from a write-only sink: a real
                    // DirectionViolation, on an optional requirement.
                    direction = LinkDirection.READ,
                    minimumScope = setOf(CanonType.MESSAGE),
                    optional = true,
                ),
            ),
        ).getOrThrow()

        assertNotNull(resolved["calendar"])
        assertNull(resolved["notify"])
    }

    @Test
    fun `granting an unknown Link fails without touching the store`() = runTest {
        val store = InMemoryLinkStore()

        val result = service(store).grant("calendar-plug", LinkId("ghost"))

        assertIs<UnknownLinkException>(result.exceptionOrNull())
        assertTrue(store.grantsForPlug("calendar-plug").getOrThrow().grants.isEmpty())
    }

    // -----------------------------------------------------------------
    // Revocation cascade
    // -----------------------------------------------------------------

    @Test
    fun `revoking a Link cascades to every Plug that held a grant`() = runTest {
        val store = InMemoryLinkStore(listOf(googleLink))
        store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))
        store.grant("gmail-plug", googleLink.id, Instant.fromEpochMilliseconds(2))

        val affected = service(store).revokeLink(googleLink.id).getOrThrow()

        assertEquals(setOf("calendar-plug", "gmail-plug"), affected.toSet())
        assertTrue(store.get(googleLink.id).getOrThrow()!!.isRevoked)
        assertTrue(store.grantsForPlug("gmail-plug").getOrThrow().isRevoked(googleLink.id))
    }

    @Test
    fun `revoking one Plug's grant leaves the other Plug working`() = runTest {
        val store = InMemoryLinkStore(listOf(googleLink))
        store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))
        store.grant("gmail-plug", googleLink.id, Instant.fromEpochMilliseconds(2))

        val svc = service(store)
        svc.revokeGrant("calendar-plug", googleLink.id).getOrThrow()

        assertTrue(svc.resolve("calendar-plug", manifest(calendarRequirement)).isFailure)
        assertTrue(
            svc.resolve(
                "gmail-plug",
                manifest(calendarRequirement.copy(name = "mail", minimumScope = setOf(CanonType.EMAIL_MESSAGE))),
            ).isSuccess,
        )
    }

    // -----------------------------------------------------------------
    // Bus lifecycle
    // -----------------------------------------------------------------

    @Test
    fun `a successful resolution announces itself on the bus`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<LinkEvent.LinkResolved>()

            bus.subscribe<LinkEvent.LinkResolved, EventSubscription.ByEventClassType>(
                agentId = "observer",
                eventType = LinkEvent.LinkResolved.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            val store = InMemoryLinkStore(listOf(googleLink))
            store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))

            service(store, bus = bus)
                .resolve("calendar-plug", manifest(calendarRequirement))
                .getOrThrow()

            val seen = withTimeout(5.seconds) { received.await() }
            assertEquals("calendar", seen.requirementName)
            assertEquals(googleLink.id, seen.linkId)
            assertEquals(Transport.OAUTH_REST, seen.transport)
        }
    }

    @Test
    fun `a failed resolution is never silent`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<LinkEvent.LinkResolutionFailed>()

            bus.subscribe<LinkEvent.LinkResolutionFailed, EventSubscription.ByEventClassType>(
                agentId = "observer",
                eventType = LinkEvent.LinkResolutionFailed.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            service(InMemoryLinkStore(), bus = bus)
                .resolve("calendar-plug", manifest(calendarRequirement))

            val seen = withTimeout(5.seconds) { received.await() }
            assertIs<LinkResolutionFailure.MissingLink>(seen.failure)
            assertEquals(LinkEvent.LinkResolutionFailed.NO_LINK, seen.linkId)
        }
    }

    @Test
    fun `a revocation announces its blast radius`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<LinkEvent.LinkRevoked>()

            bus.subscribe<LinkEvent.LinkRevoked, EventSubscription.ByEventClassType>(
                agentId = "observer",
                eventType = LinkEvent.LinkRevoked.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            val store = InMemoryLinkStore(listOf(googleLink))
            store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))
            store.grant("gmail-plug", googleLink.id, Instant.fromEpochMilliseconds(2))

            service(store, bus = bus).revokeLink(googleLink.id).getOrThrow()

            val seen = withTimeout(5.seconds) { received.await() }
            assertEquals(RevocationScope.LINK, seen.scope)
            assertEquals(setOf("calendar-plug", "gmail-plug"), seen.affectedPlugIds.toSet())
        }
    }

    @Test
    fun `resolution works with no bus wired`() = runTest {
        val store = InMemoryLinkStore(listOf(googleLink))
        store.grant("calendar-plug", googleLink.id, Instant.fromEpochMilliseconds(1))

        assertTrue(
            service(store, bus = null)
                .resolve("calendar-plug", manifest(calendarRequirement))
                .isSuccess,
        )
    }
}
