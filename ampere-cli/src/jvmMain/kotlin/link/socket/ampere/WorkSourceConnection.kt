package link.socket.ampere

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.EgressClass
import link.socket.ampere.link.InMemoryLinkStore
import link.socket.ampere.link.Link
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkResolutionService
import link.socket.ampere.link.PlatformTarget
import link.socket.ampere.link.Transport
import link.socket.ampere.plug.PlugContext
import link.socket.ampere.work.linear.LinearWorkSource
import link.socket.ampere.work.linear.SupervisorInstanceId

/**
 * Opens the work-source adapter for one supervisor command: `ampere reconcile`
 * (AMPR-310) and `ampere dispatch` (AMPR-308).
 *
 * The credential comes from [WorkSourceCredentials] — the seeded keychain item,
 * refreshed as it nears expiry, or `$AMPERE_WORK_SOURCE_TOKEN` as an override — and
 * the Link grant is built in memory for the length of the command.
 */
internal object WorkSourceConnection {

    /**
     * @param instanceId The supervisor instance every claim and escalation this adapter
     *   writes is attributed to.
     * @param why Appended to a missing-credential error: what the command needed the
     *   credential for.
     */
    suspend fun open(
        uri: String,
        instanceId: String,
        credentials: WorkSourceCredentials,
        terminal: Terminal,
        why: String,
    ): LinearWorkSource {
        val manifest = LinearWorkSource.manifest(uri)
        val linkId = WorkSourceCredentials.linkIdFor(uri)

        val opened = credentials.open(linkId, uri).getOrElse { cause ->
            throw PrintMessage("--work-source: ${cause.message} $why", statusCode = 1, printError = true)
        }
        if (opened.origin == WorkSourceCredentials.Origin.ENVIRONMENT_OVERRIDE) {
            terminal.println(
                yellow("  Credential:  \$${WorkSourceCredentials.TOKEN_ENV} (override; not refreshed, not stored)"),
            )
        } else {
            terminal.println("  Credential:  keychain ${dim("(${CredentialRef.aliasFor(linkId)})")}")
        }

        val links = InMemoryLinkStore(
            links = listOf(
                Link(
                    id = linkId,
                    transport = Transport.MCP,
                    direction = LinkDirection.READ_WRITE,
                    egress = EgressClass.ThirdParty(provider = LinearWorkSource.DEPENDENCY_NAME),
                    scope = manifest.emits,
                    credentialRef = CredentialRef.forLink(linkId),
                ),
            ),
        )
        links.grant(manifest.id, linkId).getOrThrow()

        val plugContext = PlugContext.create(
            manifest = manifest,
            credentialBinding = opened.binding,
            linkResolutionService = LinkResolutionService(
                linkStore = links,
                platform = PlatformTarget.JVM_DESKTOP,
            ),
            credentialRefresher = opened.refresher,
        ).getOrThrow()

        // A server that never came up leaves no tools, which `open` would report as schema
        // drift. Say what actually happened — a refused or unrefreshable credential, usually.
        plugContext.serverFailures.firstOrNull()?.let { failure ->
            throw PrintMessage(
                "--work-source: could not connect to $uri: ${failure.cause?.message ?: "unknown failure"}",
                statusCode = 1,
                printError = true,
            )
        }

        return LinearWorkSource.open(
            plugContext = plugContext,
            linkId = linkId,
            instanceId = SupervisorInstanceId(instanceId),
        ).getOrThrow()
    }
}
