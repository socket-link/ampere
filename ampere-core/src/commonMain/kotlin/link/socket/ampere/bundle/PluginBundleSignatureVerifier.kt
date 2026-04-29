package link.socket.ampere.bundle

import co.touchlab.kermit.Logger

/**
 * Outcome of [PluginBundleSignatureVerifier.verify].
 *
 * [Verified.Skipped] and [Verified.Trusted] are both safe to surface as
 * "verified" to the user, but marketplace UI (W1.10) is expected to badge
 * them differently — `Skipped` exists only because production crypto has
 * not landed yet, and lets unsigned bundles flow through the import pipeline
 * during W0/W1 without forcing the importer to special-case `null`.
 */
sealed interface PluginBundleSignatureVerification {

    sealed interface Verified : PluginBundleSignatureVerification {

        /** A real signature was checked against a trusted key. */
        data object Trusted : Verified

        /** No verification was performed. Production crypto is deferred. */
        data object Skipped : Verified
    }

    /** A signature was present but did not validate. Bundle must be rejected. */
    data class Invalid(val reason: String) : PluginBundleSignatureVerification
}

/**
 * Verifies the detached signature on a [PluginBundle].
 *
 * The interface exists today so marketplace UI can wire its import pipeline
 * against a stable surface. The default implementation,
 * [NoOpPluginBundleSignatureVerifier], always returns [Verified.Skipped]
 * with a logged warning. A real, key-pinned implementation lands in a
 * follow-up ticket.
 */
fun interface PluginBundleSignatureVerifier {

    suspend fun verify(bundle: PluginBundle): PluginBundleSignatureVerification
}

/**
 * Default no-op signature verifier.
 *
 * Logs a warning the first time it is invoked per process so a stray
 * production deployment cannot silently bypass signature checks. The
 * follow-up production verifier replaces this object entirely; do not extend
 * it.
 */
object NoOpPluginBundleSignatureVerifier : PluginBundleSignatureVerifier {

    private val log = Logger.withTag("ampere/bundle/signature")

    override suspend fun verify(bundle: PluginBundle): PluginBundleSignatureVerification {
        log.w {
            "Signature verification is stubbed (no-op). " +
                "Bundle '${bundle.manifest.id}' v${bundle.manifest.version} accepted without crypto."
        }
        return PluginBundleSignatureVerification.Verified.Skipped
    }
}
