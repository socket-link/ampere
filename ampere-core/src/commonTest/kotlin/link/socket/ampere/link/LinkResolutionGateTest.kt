package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonType

class LinkResolutionGateTest {

    private val grantedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val revokedAt = Instant.fromEpochMilliseconds(1_700_000_500_000)

    private val googleLink = Link(
        id = LinkId("google-oauth"),
        transport = Transport.OAUTH_REST,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.ThirdParty("google"),
        scope = setOf(CanonType.CALENDAR_EVENT, CanonType.EMAIL_MESSAGE, CanonType.PERSON),
        credentialRef = CredentialRef("keychain://google"),
    )

    private val apnsLink = Link(
        id = LinkId("apns"),
        transport = Transport.APNS,
        direction = LinkDirection.WRITE,
        egress = EgressClass.FirstParty,
        scope = setOf(CanonType.MESSAGE),
    )

    private fun grants(plugId: String, links: List<LinkId>) = LinkGrants(
        plugId = plugId,
        grants = links.map { LinkGrant(plugId, it, grantedAt) },
    )

    private fun grants(plugId: String, link: LinkId) = grants(plugId, listOf(link))

    private fun requirement(
        name: String = "calendar",
        transport: Transport = Transport.OAUTH_REST,
        direction: LinkDirection = LinkDirection.READ,
        scope: Set<CanonType> = setOf(CanonType.CALENDAR_EVENT),
        role: TransportRole = TransportRole.CONSUMER,
        optional: Boolean = false,
    ) = LinkRequirement(name, transport, direction, scope, role, optional)

    // -----------------------------------------------------------------
    // Sharing — the property the whole design exists for
    // -----------------------------------------------------------------

    @Test
    fun `one Link resolves for two different Plugs`() {
        val calendarPlug = LinkResolutionGate.resolve(
            requirement = requirement(name = "calendar", scope = setOf(CanonType.CALENDAR_EVENT)),
            candidates = listOf(googleLink),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val gmailPlug = LinkResolutionGate.resolve(
            requirement = requirement(name = "mail", scope = setOf(CanonType.EMAIL_MESSAGE)),
            candidates = listOf(googleLink),
            grants = grants("gmail-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        assertIs<LinkResolution.Resolved>(calendarPlug)
        assertIs<LinkResolution.Resolved>(gmailPlug)
        assertEquals(googleLink.id, calendarPlug.link.id)
        assertEquals(googleLink.id, gmailPlug.link.id)
    }

    @Test
    fun `a Link the Plug was never granted is invisible rather than rejected`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(googleLink),
            grants = LinkGrants.empty("stranger-plug"),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        assertIs<LinkResolutionFailure.MissingLink>(failed.failure)
    }

    // -----------------------------------------------------------------
    // Direction
    // -----------------------------------------------------------------

    @Test
    fun `a write-only Link rejects a Perceive-shaped requirement`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(
                name = "notify",
                transport = Transport.APNS,
                direction = LinkDirection.READ,
                scope = setOf(CanonType.MESSAGE),
            ),
            candidates = listOf(apnsLink),
            grants = grants("notify-plug", apnsLink.id),
            platform = PlatformTarget.JVM_DESKTOP,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.DirectionViolation>(failed.failure)
        assertEquals(LinkDirection.READ, failure.required)
        assertEquals(LinkDirection.WRITE, failure.actual)
    }

    @Test
    fun `a write-only Link satisfies an Execute-shaped requirement`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(
                name = "notify",
                transport = Transport.APNS,
                direction = LinkDirection.WRITE,
                scope = setOf(CanonType.MESSAGE),
            ),
            candidates = listOf(apnsLink),
            grants = grants("notify-plug", apnsLink.id),
            platform = PlatformTarget.JVM_DESKTOP,
        )

        assertIs<LinkResolution.Resolved>(result)
    }

    @Test
    fun `a read-write Link satisfies every direction`() {
        LinkDirection.entries.forEach { required ->
            assertTrue(LinkDirection.READ_WRITE.satisfies(required), "READ_WRITE vs $required")
        }
    }

    @Test
    fun `an already-resolved write-only Link still refuses Perceive`() {
        assertTrue(LinkResolutionGate.permits(apnsLink, LinkOperation.EXECUTE))
        assertTrue(!LinkResolutionGate.permits(apnsLink, LinkOperation.PERCEIVE))
    }

    // -----------------------------------------------------------------
    // Scope
    // -----------------------------------------------------------------

    @Test
    fun `a Link that may not carry a required canon type is refused`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(scope = setOf(CanonType.CALENDAR_EVENT, CanonType.HEALTH_SAMPLE)),
            candidates = listOf(googleLink),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.ScopeViolation>(failed.failure)
        assertEquals(setOf(CanonType.HEALTH_SAMPLE), failure.missingScope)
    }

    @Test
    fun `a Link permitted more than the requirement needs still resolves`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(scope = setOf(CanonType.CALENDAR_EVENT)),
            candidates = listOf(googleLink),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        assertIs<LinkResolution.Resolved>(result)
    }

    // -----------------------------------------------------------------
    // Revocation
    // -----------------------------------------------------------------

    @Test
    fun `a revoked Link reports LINK scope rather than the Plug grant`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(googleLink.copy(revokedAt = revokedAt)),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.RevokedCredential>(failed.failure)
        assertEquals(RevocationScope.LINK, failure.scope)
    }

    @Test
    fun `a revoked credential reports CREDENTIAL scope`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(
                googleLink.copy(credentialRef = CredentialRef("keychain://google", revokedAt)),
            ),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.RevokedCredential>(failed.failure)
        assertEquals(RevocationScope.CREDENTIAL, failure.scope)
    }

    @Test
    fun `a revoked Plug grant reports PLUG_GRANT scope and spares other Plugs`() {
        val revokedGrants = LinkGrants(
            plugId = "calendar-plug",
            grants = listOf(LinkGrant("calendar-plug", googleLink.id, grantedAt, revokedAt)),
        )

        val revokedPlug = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(googleLink),
            grants = revokedGrants,
            platform = PlatformTarget.ANDROID,
        )

        val stillGranted = LinkResolutionGate.resolve(
            requirement = requirement(name = "mail", scope = setOf(CanonType.EMAIL_MESSAGE)),
            candidates = listOf(googleLink),
            grants = grants("gmail-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(revokedPlug)
        val failure = assertIs<LinkResolutionFailure.RevokedCredential>(failed.failure)
        assertEquals(RevocationScope.PLUG_GRANT, failure.scope)
        assertIs<LinkResolution.Resolved>(stillGranted)
    }

    @Test
    fun `revocation is reported ahead of a scope violation on the same Link`() {
        // Precedence matters: a "scope" error on a revoked Link would send the
        // reader looking in the wrong place entirely.
        val result = LinkResolutionGate.resolve(
            requirement = requirement(scope = setOf(CanonType.HEALTH_SAMPLE)),
            candidates = listOf(googleLink.copy(revokedAt = revokedAt)),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        assertIs<LinkResolutionFailure.RevokedCredential>(failed.failure)
    }

    // -----------------------------------------------------------------
    // Per-platform transport capability
    // -----------------------------------------------------------------

    private val appFunctionLink = Link(
        id = LinkId("app-function"),
        transport = Transport.APP_FUNCTION,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.OnDevice,
        scope = setOf(CanonType.CALENDAR_EVENT),
    )

    @Test
    fun `an AppFunction consumer requirement resolves on Android`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(transport = Transport.APP_FUNCTION),
            candidates = listOf(appFunctionLink),
            grants = grants("calendar-plug", appFunctionLink.id),
            platform = PlatformTarget.ANDROID,
        )

        assertIs<LinkResolution.Resolved>(result)
    }

    @Test
    fun `the same AppFunction consumer requirement is refused on iOS`() {
        // iOS has no AppIntent-consumer path: cross-app orchestration belongs
        // to Siri. This is the asymmetry the capability flags exist to model.
        val result = LinkResolutionGate.resolve(
            requirement = requirement(transport = Transport.APP_FUNCTION),
            candidates = listOf(appFunctionLink),
            grants = grants("calendar-plug", appFunctionLink.id),
            platform = PlatformTarget.IOS,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.TransportUnsupported>(failed.failure)
        assertEquals(PlatformTarget.IOS, failure.platform)
        assertEquals(TransportRole.CONSUMER, failure.role)
    }

    @Test
    fun `a provider-role requirement is refused where the transport cannot provide`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(role = TransportRole.PROVIDER),
            candidates = listOf(googleLink),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.TransportUnsupported>(failed.failure)
        assertEquals(TransportRole.PROVIDER, failure.role)
    }

    // -----------------------------------------------------------------
    // Optionality and multi-candidate behaviour
    // -----------------------------------------------------------------

    @Test
    fun `an optional requirement with no candidate is skipped rather than failed`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(optional = true),
            candidates = emptyList(),
            grants = LinkGrants.empty("calendar-plug"),
            platform = PlatformTarget.ANDROID,
        )

        assertIs<LinkResolution.Skipped>(result)
    }

    @Test
    fun `a later candidate wins when an earlier one does not satisfy the requirement`() {
        val narrow = googleLink.copy(id = LinkId("google-narrow"), scope = setOf(CanonType.PERSON))
        val wide = googleLink.copy(id = LinkId("google-wide"))

        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(narrow, wide),
            grants = grants("calendar-plug", listOf(narrow.id, wide.id)),
            platform = PlatformTarget.ANDROID,
        )

        val resolved = assertIs<LinkResolution.Resolved>(result)
        assertEquals(LinkId("google-wide"), resolved.link.id)
    }

    @Test
    fun `a Link of a different transport is never a candidate`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(transport = Transport.MCP),
            candidates = listOf(googleLink),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.MissingLink>(failed.failure)
        assertEquals(Transport.MCP, failure.transport)
    }
}
