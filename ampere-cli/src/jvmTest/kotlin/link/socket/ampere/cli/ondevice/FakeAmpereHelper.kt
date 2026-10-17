package link.socket.ampere.cli.ondevice

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An `ampere-helper` that speaks the wire and owns no model (AMPR-375).
 *
 * A real `sh` script, spawned as a real process through `ProcessGroups`, because what is under
 * test is what the OS does with a child: one that exits with a request outstanding, a reply that
 * never comes, a group that has to be ended with everything in it. A fake `Process` could pass all
 * of those without any of them being true.
 *
 * The replies are canned on purpose. What this proves is the protocol and the supervision; it
 * **clears nothing about FoundationModels**, which only the real binary on a Mac can.
 *
 * `$ID` and `$OP` are pulled out of each request with `sed` rather than parsed. The frames come
 * from one `kotlinx.serialization` encoder with a known field order, so a shell is enough to read
 * them, and a shell is what keeps the stand-in obviously too simple to hide a bug.
 */
internal class FakeAmpereHelper(private val directory: Path) {

    /** One pid per spawn, so a test can tell a restart from a reuse. */
    val spawnLog: Path = directory.resolve("spawns")

    /** Every request line, in the order the stand-in read them. */
    val requestLog: Path = directory.resolve("requests")

    /** `recv <id>` and `sent <id>` lines, for proving requests never overlap. */
    val orderLog: Path = directory.resolve("order")

    /** Where a stand-in that starts a child of its own writes the child's pid. */
    val childPidFile: Path = directory.resolve("child")

    /** A scratch path for a stand-in that has to behave differently the second time. */
    val marker: Path = directory.resolve("marker")

    fun pids(): List<Long> = lines(spawnLog).mapNotNull { it.trim().toLongOrNull() }

    fun spawns(): Int = pids().size

    /** The ops of every request read so far. */
    fun ops(): List<String> = requests().map { it["op"]!!.jsonPrimitive.content }

    /** Every request read so far, decoded. */
    fun requests(): List<JsonObject> =
        lines(requestLog).filter { it.isNotBlank() }.map { AmpereHelperWire.json.parseToJsonElement(it).jsonObject }

    fun order(): List<String> = lines(orderLog).filter { it.isNotBlank() }

    fun childPid(): Long? = lines(childPidFile).firstOrNull()?.trim()?.toLongOrNull()

    /**
     * Writes a stand-in that answers one request per line with [cases], and returns it.
     *
     * @param startChild Starts a long-lived child in the helper's process group, so a test can see
     *   that ending the helper ends everything in its group.
     */
    fun script(cases: List<String>, startChild: Boolean = false): File {
        val body = buildString {
            appendLine("#!/bin/sh")
            appendLine("echo $SHELL_PID >> \"$spawnLog\"")
            if (startChild) appendLine("sleep 600 & echo $LAST_BACKGROUND_PID > \"$childPidFile\"")
            appendLine("while IFS= read -r line; do")
            appendLine("  printf '%s\\n' \"$LINE\" >> \"$requestLog\"")
            appendLine(ID_LINE)
            appendLine(OP_LINE)
            appendLine(CASE_LINE)
            cases.forEach { appendLine(it) }
            // A loud catch-all, so an op a test forgot to script fails at once rather than
            // parking the call until its deadline.
            appendLine(UNKNOWN_OP)
            appendLine("  esac")
            appendLine("done")
        }
        val file = directory.resolve("fake-ampere-helper.sh")
        Files.writeString(file, body)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file.toFile()
    }

    /** A stand-in that exits at once with [code], before reading anything. */
    fun exiting(code: Int): File {
        val file = directory.resolve("exiting-helper.sh")
        Files.writeString(
            file,
            "#!/bin/sh\necho $SHELL_PID >> \"$spawnLog\"\necho 'dyld: missing symbol' >&2\nexit $code\n",
        )
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file.toFile()
    }

    /** Answers [op] after [delaySeconds] from a background subshell, logging when it read and answered. */
    fun slowReply(op: String, delaySeconds: Int, result: String): String =
        """  $op) echo "recv ${'$'}ID" >> "$orderLog"; { sleep $delaySeconds; echo "sent ${'$'}ID" >> "$orderLog"; """ +
            """printf '{"v":1,"id":"%s","ok":true,"result":$result}\n' "${'$'}ID"; } & ;;"""

    /**
     * Exits mid-request the first time it is asked [op], and answers normally on every later
     * process.
     */
    fun dieOnce(op: String, result: String): String =
        """  $op) if [ -f "$marker" ]; then
      printf '{"v":1,"id":"%s","ok":true,"result":$result}\n' "${'$'}ID"
    else
      touch "$marker"
      exit 7
    fi ;;"""

    private fun lines(path: Path): List<String> = if (Files.exists(path)) Files.readAllLines(path) else emptyList()

    companion object {
        /** `$$` and `$!` in shell, written so Kotlin does not read them as templates. */
        private const val SHELL_PID = "${'$'}${'$'}"
        private const val LAST_BACKGROUND_PID = "${'$'}!"
        private const val LINE = "${'$'}line"

        private const val ID_LINE =
            """  ID=`printf '%s' "${'$'}line" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'`"""
        private const val OP_LINE =
            """  OP=`printf '%s' "${'$'}line" | sed -n 's/.*"op":"\([^"]*\)".*/\1/p'`"""
        private const val CASE_LINE = """  case "${'$'}OP" in"""

        private const val UNKNOWN_OP =
            """  *) printf '{"v":1,"id":"%s","ok":false,"error":{"code":"unknown_op",""" +
                """"message":"the stand-in scripts no %s"}}\n' "${'$'}ID" "${'$'}OP" ;;"""

        /** `probe` as the shipped helper answers it. The range and services are what "too old" means here. */
        fun probe(min: Int = 1, max: Int = 1, services: String = """["inference"]"""): String =
            reply("probe", """{"protocolMin":$min,"protocolMax":$max,"build":"fake","services":$services}""")

        /** `inference.probe` as the Swift side answers it: a token, and a window only when available. */
        fun inferenceProbe(availability: String, contextSize: Int? = null): String =
            reply(
                "inference.probe",
                if (contextSize == null) {
                    """{"availability":"$availability"}"""
                } else {
                    """{"availability":"$availability","contextSize":$contextSize}"""
                },
            )

        /** `inference.generate` answering [text], whatever the prompt. Keep [text] free of `%`, `\` and `"`. */
        fun inferenceGenerate(text: String): String = reply("inference.generate", """{"text":"$text"}""")

        fun inferenceCount(tokens: Int?): String = reply("inference.count", """{"tokens":${tokens ?: "null"}}""")

        fun reply(op: String, result: String): String =
            """  $op) printf '{"v":1,"id":"%s","ok":true,"result":$result}\n' "${'$'}ID" ;;"""

        /** The helper answers [op] with an error frame. */
        fun refuse(op: String, code: String, message: String): String =
            """  $op) printf '{"v":1,"id":"%s","ok":false,"error":{"code":"$code","message":"$message"}}\n' """ +
                """"${'$'}ID" ;;"""

        /** Takes the request and never answers it, so only the deadline can end the call. */
        fun silent(op: String): String = """  $op) : ;;"""
    }
}
