package link.socket.ampere.agents.execution.verify

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.agents.execution.process.ProcessGroups

/** Whether a [Precondition] holds on this host. */
sealed interface PreconditionCheck {

    data object Satisfied : PreconditionCheck

    /**
     * It does not.
     *
     * @property why What was looked for and not found, composed from fixed text and
     *   manifest values — never a command's output.
     */
    data class Absent(val why: String) : PreconditionCheck
}

/** Checks one precondition against the repository at `root`. */
fun interface PreconditionChecker {
    suspend fun check(precondition: Precondition, root: Path): PreconditionCheck
}

/**
 * The production [PreconditionChecker]: the four schema-v1 predicates, checked
 * against this host.
 *
 * Each check is cheap and unambiguous, as the schema requires; none of them tries to
 * answer what the build tool will answer for itself.
 *
 * - **`toolchain`** — a JVM of that feature version is findable the ways Gradle's own
 *   toolchain detection finds one: the running JVM, `$JAVA_HOME`, a CI runner's
 *   `$JAVA_HOME_<v>_X64`/`_ARM64`, macOS's `java_home -v`, or a JDK directory under
 *   `/usr/lib/jvm` or Gradle's `~/.gradle/jdks`. Gradle may still provision one this
 *   misses, which is the conservative direction: a refused run is reported, never
 *   passed.
 * - **`file`** — the path exists, relative to the repository root and inside it.
 * - **`command`** — the command, split and substituted like a gate's, exits 0 within
 *   [commandTimeout].
 * - **`simulator`** — `xcrun simctl list devices available --json` names a device
 *   matching the glob (`*` any run of characters, `?` one).
 */
class HostPreconditions(
    private val environment: Map<String, String> = System.getenv(),
    private val commandTimeout: Duration = DEFAULT_COMMAND_TIMEOUT,
    private val userHome: Path = Path.of(System.getProperty("user.home") ?: "."),
) : PreconditionChecker {

    override suspend fun check(precondition: Precondition, root: Path): PreconditionCheck =
        when (val predicate = precondition.predicate) {
            is PreconditionPredicate.Toolchain -> toolchain(predicate.version)
            is PreconditionPredicate.File -> file(predicate.path, root)
            is PreconditionPredicate.Command -> command(predicate.command, root)
            is PreconditionPredicate.Simulator -> simulator(predicate.glob, root)
        }

    private suspend fun toolchain(version: String): PreconditionCheck {
        val feature = version.trim()
        val found = runningJvmIs(feature) ||
            environmentJdk(feature) ||
            javaHomeIs(feature) ||
            jdkDirectoryFor(feature) ||
            macJavaHome(feature)
        return if (found) {
            PreconditionCheck.Satisfied
        } else {
            PreconditionCheck.Absent("no JVM toolchain $feature was found on this host")
        }
    }

    private fun runningJvmIs(feature: String): Boolean = Runtime.version().feature().toString() == feature

    private fun environmentJdk(feature: String): Boolean =
        listOf("JAVA_HOME_${feature}_X64", "JAVA_HOME_${feature}_ARM64", "JAVA_HOME_${feature}_AARCH64")
            .mapNotNull { environment[it] }
            .any { Files.isDirectory(Path.of(it)) }

    private fun javaHomeIs(feature: String): Boolean {
        val home = environment["JAVA_HOME"]?.takeIf { it.isNotBlank() } ?: return false
        return releaseVersion(Path.of(home))?.let { featureOf(it) == feature } ?: false
    }

    private fun jdkDirectoryFor(feature: String): Boolean {
        val pattern = Regex("(^|[^0-9])$feature([^0-9]|$)")
        return listOf(Path.of("/usr/lib/jvm"), userHome.resolve(".gradle/jdks")).any { parent ->
            Files.isDirectory(parent) && runCatching {
                Files.list(parent).use { children ->
                    children.anyMatch { child ->
                        if (!Files.isDirectory(child) || !pattern.containsMatchIn(child.fileName.toString())) {
                            return@anyMatch false
                        }
                        // A versioned name is a hint; the release file, where there is one, decides.
                        val release = releaseVersion(child) ?: releaseVersion(child.resolve("Contents/Home"))
                        release == null || featureOf(release) == feature
                    }
                }
            }.getOrDefault(false)
        }
    }

    private suspend fun macJavaHome(feature: String): Boolean {
        val tool = File(MAC_JAVA_HOME)
        if (!tool.canExecute()) return false
        return HostCommand.run(listOf(MAC_JAVA_HOME, "-v", feature), directory = null, timeout = commandTimeout)
            .exitCode == 0
    }

    private fun file(path: String, root: Path): PreconditionCheck {
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(path).normalize()
        return when {
            !target.startsWith(base) -> PreconditionCheck.Absent("`$path` is outside the repository")
            Files.exists(target) -> PreconditionCheck.Satisfied
            else -> PreconditionCheck.Absent("`$path` does not exist in the repository")
        }
    }

    private suspend fun command(command: String, root: Path): PreconditionCheck {
        val argv = try {
            GateCommand.argv(command)
        } catch (e: GateCommand.Malformed) {
            return PreconditionCheck.Absent("`$command` is not one command")
        }
        val substituted = when (val result = GateCommand.substitute(argv, environment)) {
            is GateCommand.Substitution.Done -> result.argv
            is GateCommand.Substitution.Unset ->
                return PreconditionCheck.Absent("`$command` names unset ${result.names.joinToString { "\${env.$it}" }}")
        }
        val ran = HostCommand.run(substituted, directory = root, timeout = commandTimeout, environment = environment)
        return when {
            ran.timedOut -> PreconditionCheck.Absent("`$command` did not finish within $commandTimeout")
            ran.exitCode == 0 -> PreconditionCheck.Satisfied
            else -> PreconditionCheck.Absent("`$command` exited ${ran.exitCode ?: "without a status"}")
        }
    }

    private suspend fun simulator(glob: String, root: Path): PreconditionCheck {
        val ran = HostCommand.run(
            listOf("xcrun", "simctl", "list", "devices", "available", "--json"),
            directory = root,
            timeout = commandTimeout,
            environment = environment,
        )
        if (ran.exitCode != 0) return PreconditionCheck.Absent("no simulator list is available on this host")
        val names = simulatorNames(ran.output)
        val pattern = globToRegex(glob)
        return if (names.any { pattern.matches(it) }) {
            PreconditionCheck.Satisfied
        } else {
            PreconditionCheck.Absent("no available simulator matches `$glob`")
        }
    }

    companion object {

        val DEFAULT_COMMAND_TIMEOUT: Duration = 30.seconds

        private const val MAC_JAVA_HOME = "/usr/libexec/java_home"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** The names of the available devices in `simctl list devices available --json` output. */
        internal fun simulatorNames(json: String): List<String> {
            val root = runCatching { JSON.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
            val devices = root["devices"] as? JsonObject ?: return emptyList()
            return devices.values.flatMap { runtime ->
                (runtime as? JsonArray).orEmpty().mapNotNull { device ->
                    val fields = device as? JsonObject ?: return@mapNotNull null
                    val available = (fields["isAvailable"] as? JsonPrimitive)?.booleanOrNull ?: true
                    if (!available) return@mapNotNull null
                    (fields["name"] as? JsonPrimitive)?.contentOrNull
                }
            }
        }

        /** A shell-style glob as a whole-string regex: `*` any run, `?` one character, everything else literal. */
        internal fun globToRegex(glob: String): Regex = Regex(
            buildString {
                glob.forEach { c ->
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            },
        )

        /** `JAVA_VERSION` from a JDK's `release` file, or null when there is none. */
        private fun releaseVersion(home: Path): String? {
            val release = home.resolve("release")
            if (!Files.isRegularFile(release)) return null
            return runCatching {
                Files.readAllLines(release).firstNotNullOfOrNull { line ->
                    line.takeIf { it.startsWith("JAVA_VERSION=") }?.substringAfter('=')?.trim('"', ' ')
                }
            }.getOrNull()
        }

        /** `21` for `21.0.4`, `1.8` style versions as `8`. */
        private fun featureOf(version: String): String {
            val parts = version.split('.', '+', '-', '_')
            return if (parts.firstOrNull() == "1" && parts.size > 1) parts[1] else parts.first()
        }
    }
}

/**
 * A short host command, run in its own process group with a deadline: what a
 * precondition check runs. Not for gates, whose output is logged and whose timeout is
 * an outcome — see [GateRunner].
 */
internal object HostCommand {

    data class Ran(val exitCode: Int?, val output: String, val timedOut: Boolean)

    suspend fun run(
        argv: List<String>,
        directory: Path?,
        timeout: Duration,
        environment: Map<String, String>? = null,
    ): Ran = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        directory?.let { builder.directory(it.toFile()) }
        environment?.let {
            builder.environment().clear()
            builder.environment().putAll(it)
        }
        val grouped = try {
            ProcessGroups.start(builder)
        } catch (e: Exception) {
            return@withContext Ran(exitCode = null, output = "", timedOut = false)
        }
        runCatching { grouped.process.outputStream.close() }
        val output = StringBuilder()
        val reader = thread(isDaemon = true, name = "ampere-precondition") {
            runCatching {
                grouped.process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        synchronized(output) { if (output.length < MAX_OUTPUT) output.append(buffer, 0, read) }
                    }
                }
            }
        }
        val exited = grouped.process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!exited) {
            grouped.terminate(ProcessGroups.DEFAULT_GRACE)
            return@withContext Ran(exitCode = null, output = "", timedOut = true)
        }
        reader.join(DRAIN_MILLIS)
        val captured = synchronized(output) { output.toString() }
        Ran(exitCode = grouped.process.exitValue(), output = captured, timedOut = false)
    }

    private const val MAX_OUTPUT = 1_000_000

    private const val DRAIN_MILLIS = 2_000L
}
