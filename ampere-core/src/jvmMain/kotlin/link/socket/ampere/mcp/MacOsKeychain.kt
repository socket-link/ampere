package link.socket.ampere.mcp

import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * [SecretStore] over the macOS keychain, through `/usr/bin/security` (AMPR-464).
 *
 * Each secret is a generic-password item in the default (login) keychain,
 * found by service and account. macOS only for v1: on any other OS every call
 * fails, saying so, rather than falling back to somewhere less safe.
 *
 * - **The secret never reaches a command line.** A write runs `security -i`
 *   and sends `add-generic-password … -X <hex>` on its stdin, so the secret is
 *   not in the argument list `ps` shows every user on the machine.
 * - **A write replaces in one step.** `-U` updates an existing item in place,
 *   which is what [SecretStore.write] promises.
 * - **What is stored is base64.** `find-generic-password -w` prints an item's
 *   data verbatim only when it is printable and as hex otherwise, so the
 *   secret is stored as base64 of its UTF-8 bytes and always reads back as
 *   written.
 * - **Reads need no prompt.** The item is created by `/usr/bin/security`,
 *   which the keychain therefore trusts to read it back.
 *
 * Service and account are quoted into the interactive command, so they may not
 * contain `"`, `\` or a line break; an alias from [link.socket.ampere.link.CredentialRef.aliasFor]
 * and an MCP URI never do.
 */
class MacOsKeychain(
    private val security: SecurityCommand = SystemSecurityCommand(),
    private val operatingSystem: String = System.getProperty("os.name").orEmpty(),
) : SecretStore {

    override suspend fun read(service: String, account: String): Result<String?> = attempt {
        val result = security.run(listOf("find-generic-password", "-s", service, "-a", account, "-w"), stdin = null)
        when (result.exitCode) {
            0 -> decode(result.stdout.trimEnd('\n', '\r'), service)
            ITEM_NOT_FOUND -> null
            else -> throw failure("read", service, result)
        }
    }

    override suspend fun write(service: String, account: String, secret: String): Result<Unit> = attempt {
        requireQuotable(service, "service")
        requireQuotable(account, "account")
        val data = encode(secret)
        val command = "add-generic-password -U -s \"$service\" -a \"$account\" -X $data\n"
        val result = security.run(listOf("-i"), stdin = command)
        if (result.exitCode != 0) throw failure("write", service, result, scrub = data)
    }

    override suspend fun delete(service: String, account: String): Result<Unit> = attempt {
        val result = security.run(listOf("delete-generic-password", "-s", service, "-a", account), stdin = null)
        if (result.exitCode != 0 && result.exitCode != ITEM_NOT_FOUND) throw failure("delete", service, result)
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> {
        if (!operatingSystem.startsWith("Mac")) {
            return Result.failure(
                UnsupportedOperationException(
                    "The keychain credential store is macOS-only for now (it uses /usr/bin/security), " +
                        "and this is $operatingSystem",
                ),
            )
        }
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requireQuotable(value: String, name: String) {
        require(value.none { it == '"' || it == '\\' || it == '\n' || it == '\r' }) {
            "A keychain $name may not contain a quote, a backslash or a line break: $value"
        }
    }

    /** UTF-8, then base64, then hex for `-X`: printable on the way back, and quote-free on the way in. */
    private fun encode(secret: String): String =
        Base64.getEncoder().encode(secret.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun decode(stored: String, service: String): String =
        try {
            String(Base64.getDecoder().decode(stored), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IOException("The keychain item under $service was not written by Ampere, so it cannot be read")
        }

    private fun failure(
        operation: String,
        service: String,
        result: SecurityResult,
        scrub: String? = null,
    ): IOException {
        val detail = result.stderr.trim().let { stderr ->
            if (scrub != null) stderr.replace(scrub, "<redacted>") else stderr
        }
        return IOException("Keychain $operation for $service failed (security exited ${result.exitCode}): $detail")
    }

    private companion object {

        /** `errSecItemNotFound`, as `security` reports it in its exit status. */
        const val ITEM_NOT_FOUND = 44
    }
}

/** One run of the `security` tool. A seam so [MacOsKeychain] is testable without a keychain. */
fun interface SecurityCommand {

    /** Run `security` with [arguments], writing [stdin] (if any) to it, and report how it exited. */
    suspend fun run(arguments: List<String>, stdin: String?): SecurityResult
}

/** How a `security` run exited. [stdout] holds a secret after a read, so [toString] leaves it out. */
data class SecurityResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    override fun toString(): String = "SecurityResult(exitCode=$exitCode, stdout=<redacted>, stderr=$stderr)"
}

/** The production [SecurityCommand]: runs the real tool, killed after [timeout]. */
class SystemSecurityCommand(
    private val executable: String = "/usr/bin/security",
    private val timeout: Duration = 60.seconds,
) : SecurityCommand {

    override suspend fun run(arguments: List<String>, stdin: String?): SecurityResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(executable) + arguments).start()
        try {
            coroutineScope {
                val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
                process.outputStream.use { stream -> stdin?.let { stream.write(it.toByteArray(Charsets.UTF_8)) } }
                if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    throw IOException("$executable did not finish within $timeout")
                }
                SecurityResult(process.exitValue(), stdout.await(), stderr.await())
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
