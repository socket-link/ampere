package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * An ACP agent that is a real `sh` process and owns no model (AMPR-493).
 *
 * Spawned through `StdioAcpTransport` like the real thing, because what is under test is what
 * the OS does with a child: a line that has to be answered on stdout, a process that exits
 * mid-turn, a group that has to be ended with everything in it, and — the fence's reason to
 * exist — a file written straight to disk whether or not the client said yes.
 *
 * The replies are canned. Each request's `id` is cut out of the line with `sed`, relying on
 * the client's fixed field order (`jsonrpc`, `id`, `method`, ...), which a shell can read and
 * which keeps the stand-in too simple to hide a bug. Everything the agent reads is appended to
 * [log], the client's permission answer included.
 */
internal class FakeAcpAgentScript(private val directory: Path) {

    val log: Path = directory.resolve("agent.log")

    fun logLines(): List<String> = if (Files.exists(log)) Files.readAllLines(log) else emptyList()

    /**
     * The agent: answers `initialize` and `session/new`, runs [onPrompt] (shell) for each
     * `session/prompt`, then answers it with [stopReason] unless [onPrompt] ended the process.
     *
     * @param preamble Shell run once before the read loop — to start a child, say.
     * @param stderrNoise Whether to write a line to stderr for every line read.
     */
    fun agent(
        onPrompt: String = "",
        stopReason: String = "end_turn",
        preamble: String = "",
        stderrNoise: Boolean = false,
        name: String = "fake-acp-agent.sh",
    ): File {
        val d = "$"
        val noise = if (stderrNoise) "echo 'agent noise' >&2" else ":"
        val body = """
            |#!/bin/sh
            |LOG="$log"
            |$preamble
            |while IFS= read -r line; do
            |  printf '%s\n' "${d}line" >> "${d}LOG"
            |  $noise
            |  id=$d(printf '%s' "${d}line" | sed -n 's/^{"jsonrpc":"2.0","id":\([0-9][0-9]*\),.*/\1/p')
            |  case "${d}line" in
            |    *'"method":"initialize"'*)
            |      printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentInfo":{"name":"fake-acp","version":"0.1"}}}\n' "${d}id" ;;
            |    *'"method":"session/new"'*)
            |      printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"sess-1"}}\n' "${d}id" ;;
            |    *'"method":"session/prompt"'*)
            |      $onPrompt
            |      printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"$stopReason"}}\n' "${d}id" ;;
            |    *'"method":"session/cancel"'*)
            |      echo "cancel-seen" >> "${d}LOG" ;;
            |  esac
            |done
            |
        """.trimMargin()
        return write(name, body)
    }

    /** A process that reads one line and exits with [code], saying why on stderr. */
    fun exiting(code: Int): File = write(
        "exiting-agent.sh",
        "#!/bin/sh\nread line\necho 'dyld: missing symbol' >&2\nexit $code\n",
    )

    /**
     * Shell for [agent]'s `onPrompt`: announce an edit of [path], ask permission for it, and
     * write [content] to [path] when allowed — or regardless, when [writeEvenIfRejected], which
     * is how a harness that writes from its own process behaves.
     */
    fun editWithPermission(path: String, content: String, writeEvenIfRejected: Boolean = false): String {
        val d = "$"
        val write = "mkdir -p \"$d(dirname \"$path\")\"; printf '%s\\n' '$content' > \"$path\""
        val rejected = if (writeEvenIfRejected) write else ":"
        return """
            |printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"tool_call","toolCallId":"c1","title":"Edit","kind":"edit","status":"pending","locations":[{"path":"$path"}]}}}\n'
            |      printf '{"jsonrpc":"2.0","id":100,"method":"session/request_permission","params":{"sessionId":"sess-1","toolCall":{"toolCallId":"c1","kind":"edit","locations":[{"path":"$path"}]},"options":[{"optionId":"once","name":"Allow","kind":"allow_once"},{"optionId":"reject","name":"Reject","kind":"reject_once"}]}}\n'
            |      IFS= read -r answer
            |      printf '%s\n' "${d}answer" >> "${d}LOG"
            |      case "${d}answer" in
            |        *'"optionId":"once"'*) $write ;;
            |        *) $rejected ;;
            |      esac
            |      printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Edited the file."}}}}\n'
        """.trimMargin()
    }

    private fun write(name: String, body: String): File {
        val file = directory.resolve(name)
        Files.writeString(file, body)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file.toFile()
    }
}
