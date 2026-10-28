package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonType
import link.socket.ampere.plug.PlugId

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
        plugId = PlugId(plugId),
        grants = links.map { LinkGrant(PlugId(plugId), it, grantedAt) },
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
            grants = LinkGrants.empty(PlugId("stranger-plug")),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        assertIs<LinkResolutionFailure.UngrantedLink>(failed.failure)
    }

    // -----------------------------------------------------------------
    // Ungranted versus missing — the consent question, told apart
    // -----------------------------------------------------------------

    @Test
    fun `an ungranted Link names the Link a caller would ask consent on`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(googleLink),
            grants = LinkGrants.empty(PlugId("stranger-plug")),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.UngrantedLink>(failed.failure)
        assertEquals(googleLink.id, failure.linkId)
        assertEquals("calendar", failure.requirementName)
    }

    @Test
    fun `no Link of the transport at all is missing rather than ungranted`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(apnsLink),
            grants = LinkGrants.empty(PlugId("calendar-plug")),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.MissingLink>(failed.failure)
        assertEquals(Transport.OAUTH_REST, failure.transport)
        assertEquals(LinkDirection.READ, failure.direction)
    }

    @Test
    fun `granting the reported Link resolves the requirement`() {
        val plug = "stranger-plug"

        val before = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(googleLink),
            grants = LinkGrants.empty(PlugId(plug)),
            platform = PlatformTarget.ANDROID,
        )
        val failure = assertIs<LinkResolutionFailure.UngrantedLink>(
            assertIs<LinkResolution.Failed>(before).failure,
        )

        val after = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(googleLink),
            grants = grants(plug, failure.linkId),
            platform = PlatformTarget.ANDROID,
        )

        assertEquals(failure.linkId, assertIs<LinkResolution.Resolved>(after).link.id)
    }

    @Test
    fun `an ungranted candidate that would still fail loses to one a grant would fix`() {
        val narrow = googleLink.copy(id = LinkId("google-narrow"), scope = setOf(CanonType.PERSON))
        val wide = googleLink.copy(id = LinkId("google-wide"))

        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(narrow, wide),
            grants = LinkGrants.empty(PlugId("calendar-plug")),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.UngrantedLink>(failed.failure)
        assertEquals(LinkId("google-wide"), failure.linkId)
    }

    @Test
    fun `ungranted candidates that no grant would fix are missing rather than ungranted`() {
        // Asking consent for either would record a grant the scope check
        // rejects on the very next resolution.
        val narrow = googleLink.copy(id = LinkId("google-narrow"), scope = setOf(CanonType.PERSON))
        val alsoNarrow = googleLink.copy(id = LinkId("google-narrower"), scope = emptySet())

        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(narrow, alsoNarrow),
            grants = LinkGrants.empty(PlugId("calendar-plug")),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.MissingLink>(failed.failure)
        assertEquals("calendar", failure.requirementName)
        assertEquals(Transport.OAUTH_REST, failure.transport)
    }

    @Test
    fun `an ungranted revoked Link is missing rather than a consent question`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(googleLink.copy(revokedAt = revokedAt)),
            grants = LinkGrants.empty(PlugId("calendar-plug")),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        assertIs<LinkResolutionFailure.MissingLink>(failed.failure)
    }

    @Test
    fun `a revoked Link is not preferred over a grantable sibling`() {
        val dead = googleLink.copy(id = LinkId("google-dead"), revokedAt = revokedAt)
        val live = googleLink.copy(id = LinkId("google-live"))

        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(dead, live),
            grants = LinkGrants.empty(PlugId("calendar-plug")),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.UngrantedLink>(failed.failure)
        assertEquals(LinkId("google-live"), failure.linkId)
    }

    @Test
    fun `an optional requirement is skipped even when an ungranted Link exists`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(optional = true),
            candidates = listOf(googleLink),
            grants = LinkGrants.empty(PlugId("stranger-plug")),
            platform = PlatformTarget.ANDROID,
        )

        assertIs<LinkResolution.Skipped>(result)
    }

    // -----------------------------------------------------------------
    // Requirement names — a minted Link answers only its own requirement
    // -----------------------------------------------------------------

    /** A canon-external `NATIVE_FRAMEWORK` requirement: an empty scope passes every Link's. */
    private fun nativeRequirement(name: String) = requirement(
        name = name,
        transport = Transport.NATIVE_FRAMEWORK,
        direction = LinkDirection.READ_WRITE,
        scope = emptySet(),
    )

    private fun nativeLink(name: String) = Link(
        id = LinkId.forRequirement(nativeRequirement(name)),
        transport = Transport.NATIVE_FRAMEWORK,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.OnDevice,
    )

    private val calendarNative = nativeLink("calendar")
    private val workspaceNative = nativeLink("workspace")

    @Test
    fun `a requirement whose own Link is absent is missing even beside another Plug's Link`() {
        val result = LinkResolutionGate.resolve(
            requirement = nativeRequirement("workspace"),
            candidates = listOf(calendarNative),
            grants = LinkGrants.empty(PlugId("workspace-plug")),
            platform = PlatformTarget.IOS,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.MissingLink>(failed.failure)
        assertEquals("workspace", failure.requirementName)
        assertEquals(Transport.NATIVE_FRAMEWORK, failure.transport)
    }

    @Test
    fun `a grant on another requirement's Link does not resolve this one`() {
        val result = LinkResolutionGate.resolve(
            requirement = nativeRequirement("workspace"),
            candidates = listOf(calendarNative),
            grants = grants("workspace-plug", calendarNative.id),
            platform = PlatformTarget.IOS,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        assertIs<LinkResolutionFailure.MissingLink>(failed.failure)
    }

    @Test
    fun `two Plugs granted each other's Links do not cross-resolve`() {
        val candidates = listOf(calendarNative, workspaceNative)

        val workspace = LinkResolutionGate.resolve(
            requirement = nativeRequirement("workspace"),
            candidates = candidates,
            grants = grants("workspace-plug", calendarNative.id),
            platform = PlatformTarget.IOS,
        )
        val calendar = LinkResolutionGate.resolve(
            requirement = nativeRequirement("calendar"),
            candidates = candidates,
            grants = grants("calendar-plug", workspaceNative.id),
            platform = PlatformTarget.IOS,
        )

        // Each is asked about its own Link, which a grant then resolves.
        val workspaceFailure = assertIs<LinkResolutionFailure.UngrantedLink>(
            assertIs<LinkResolution.Failed>(workspace).failure,
        )
        val calendarFailure = assertIs<LinkResolutionFailure.UngrantedLink>(
            assertIs<LinkResolution.Failed>(calendar).failure,
        )
        assertEquals(workspaceNative.id, workspaceFailure.linkId)
        assertEquals(calendarNative.id, calendarFailure.linkId)
    }

    @Test
    fun `a stale grant on another Plug's Link does not hide the Plug's own`() {
        val result = LinkResolutionGate.resolve(
            requirement = nativeRequirement("workspace"),
            candidates = listOf(calendarNative, workspaceNative),
            grants = grants("workspace-plug", listOf(calendarNative.id, workspaceNative.id)),
            platform = PlatformTarget.IOS,
        )

        assertEquals(workspaceNative.id, assertIs<LinkResolution.Resolved>(result).link.id)
    }

    @Test
    fun `two Plugs that declare the same requirement name share its Link`() {
        listOf("calendar-plug", "standup-plug").forEach { plug ->
            val result = LinkResolutionGate.resolve(
                requirement = nativeRequirement("calendar"),
                candidates = listOf(workspaceNative, calendarNative),
                grants = grants(plug, calendarNative.id),
                platform = PlatformTarget.IOS,
            )

            assertEquals(calendarNative.id, assertIs<LinkResolution.Resolved>(result).link.id, plug)
        }
    }

    @Test
    fun `the Link minted for the requirement is tried before an opaque one`() {
        val minted = googleLink.copy(id = LinkId.forRequirement(requirement()))
        val candidates = listOf(googleLink, minted)

        val ungranted = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = candidates,
            grants = LinkGrants.empty(PlugId("calendar-plug")),
            platform = PlatformTarget.ANDROID,
        )
        val granted = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = candidates,
            grants = grants("calendar-plug", listOf(googleLink.id, minted.id)),
            platform = PlatformTarget.ANDROID,
        )

        val failure = assertIs<LinkResolutionFailure.UngrantedLink>(
            assertIs<LinkResolution.Failed>(ungranted).failure,
        )
        assertEquals(minted.id, failure.linkId)
        assertEquals(minted.id, assertIs<LinkResolution.Resolved>(granted).link.id)
    }

    @Test
    fun `an opaque Link still answers a requirement whose own Link fails`() {
        val minted = googleLink.copy(id = LinkId.forRequirement(requirement()), revokedAt = revokedAt)

        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(minted, googleLink),
            grants = grants("calendar-plug", listOf(minted.id, googleLink.id)),
            platform = PlatformTarget.ANDROID,
        )

        assertEquals(googleLink.id, assertIs<LinkResolution.Resolved>(result).link.id)
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

    @Test
    fun `an already-resolved Link with a revoked folder ref no longer permits`() {
        val revokedFolder = googleLink.copy(folderRef = FolderRef("mount-1", revokedAt))

        assertTrue(!LinkResolutionGate.permits(revokedFolder, LinkOperation.PERCEIVE))
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
    fun `a revoked folder ref reports FOLDER scope`() {
        val result = LinkResolutionGate.resolve(
            requirement = requirement(),
            candidates = listOf(
                googleLink.copy(folderRef = FolderRef("mount-1", revokedAt)),
            ),
            grants = grants("calendar-plug", googleLink.id),
            platform = PlatformTarget.ANDROID,
        )

        val failed = assertIs<LinkResolution.Failed>(result)
        val failure = assertIs<LinkResolutionFailure.RevokedCredential>(failed.failure)
        assertEquals(RevocationScope.FOLDER, failure.scope)
    }

    @Test
    fun `a revoked Plug grant reports PLUG_GRANT scope and spares other Plugs`() {
        val revokedGrants = LinkGrants(
            plugId = PlugId("calendar-plug"),
            grants = listOf(LinkGrant(PlugId("calendar-plug"), googleLink.id, grantedAt, revokedAt)),
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
            grants = LinkGrants.empty(PlugId("calendar-plug")),
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
