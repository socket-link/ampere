package link.socket.ampere.cli.ondevice

import java.io.File
import java.util.Locale

/**
 * Where `ampere-helper` is on this machine, or why there is none (AMPR-375).
 *
 * Every answer is a reason, never an exception. A Linux or Windows host has no helper, and neither
 * does a Mac whose CLI was built without one. Each must behave exactly as the CLI did before the
 * helper existed.
 *
 * ## Search order
 *
 * 1. `-Dampere.helper` or `AMPERE_HELPER` names a binary directly. The tests name a fake helper
 *    script this way, which is what lets the whole JVM half be covered without the model. A name
 *    that points at nothing usable is reported as such rather than skipped: a setting made on
 *    purpose must not quietly fall through to a different binary.
 * 2. Beside the installed CLI. `installJvmDist` copies the helper into the distribution's `bin/`,
 *    next to the `ampere` start script, and the CLI's own jar in `lib/` says where that is.
 * 3. The SwiftPM build output under the working directory, for a CLI run from the repository root
 *    without installing it.
 */
internal class AmpereHelperLocator(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val property: (String) -> String? = { System.getProperty(it) },
    private val environment: (String) -> String? = { System.getenv(it) },
    private val workingDirectory: File = File("."),
    private val installDirectory: File? = runningInstallDirectory(),
) {
    /** The helper to spawn, or why there is none. Cheap: it only looks at the file system. */
    fun locate(): AmpereHelperBinary {
        if (!osName.lowercase(Locale.ROOT).contains("mac")) {
            return AmpereHelperBinary.Absent(
                "The on-device model helper only runs on macOS; this host reports '$osName'.",
            )
        }

        val named = property(HELPER_PROPERTY) ?: environment(HELPER_ENVIRONMENT)
        if (named != null) {
            val file = File(named)
            return when {
                !file.isFile -> AmpereHelperBinary.Absent(
                    "No helper at '$named', which $HELPER_PROPERTY or $HELPER_ENVIRONMENT names.",
                )
                !file.canExecute() -> AmpereHelperBinary.Absent("The helper at '$named' is not executable.")
                else -> AmpereHelperBinary.Present(file)
            }
        }

        val candidates = buildList {
            installDirectory?.let { add(it.resolve("bin").resolve(BINARY_NAME)) }
            add(workingDirectory.resolve(SOURCE_TREE_PATH))
        }
        candidates.firstOrNull { it.isFile && it.canExecute() }?.let { return AmpereHelperBinary.Present(it) }

        return AmpereHelperBinary.Absent(
            "No on-device model helper was built for this CLI. Looked in ${candidates.joinToString { it.path }}.",
        )
    }

    companion object {
        const val HELPER_PROPERTY: String = "ampere.helper"
        const val HELPER_ENVIRONMENT: String = "AMPERE_HELPER"
        const val BINARY_NAME: String = "ampere-helper"

        /** Where `swift build -c release` leaves the binary, relative to the repository root. */
        const val SOURCE_TREE_PATH: String = "ampere-cli/macos-helper/.build/release/$BINARY_NAME"

        /**
         * The distribution this class was loaded from: the parent of the `lib/` its jar sits in.
         * Null when it was loaded from a classes directory, as under Gradle, an IDE or the tests.
         */
        private fun runningInstallDirectory(): File? =
            runCatching {
                val source = File(AmpereHelperLocator::class.java.protectionDomain.codeSource.location.toURI())
                source.takeIf { it.isFile && it.parentFile?.name == "lib" }?.parentFile?.parentFile
            }.getOrNull()
    }
}

internal sealed interface AmpereHelperBinary {
    data class Present(val file: File) : AmpereHelperBinary

    /** @property reason Said plainly, for `:ask` and a log line. */
    data class Absent(val reason: String) : AmpereHelperBinary
}
