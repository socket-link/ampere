package link.socket.ampere

import java.io.File
import kotlin.test.assertEquals
import link.socket.ampere.config.AIProviderConfig
import link.socket.ampere.config.AmpereConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * AMPR-300: the CLI resolves the one directory agents may write to explicitly —
 * flag, then config file, then its own working directory — and passes it on
 * rather than letting core fall back to `user.dir` or a shared default.
 */
class WorkspaceResolutionTest {

    private fun config(workspace: String?) = AmpereConfig(
        ai = AIProviderConfig(provider = "anthropic", model = "sonnet-5"),
        team = emptyList(),
        workspace = workspace,
    )

    @Test
    fun `the --workspace flag wins over the config file`(@TempDir tempDir: File) {
        val flagDir = File(tempDir, "flag").also { it.mkdirs() }
        val configDir = File(tempDir, "config").also { it.mkdirs() }
        val resolved = resolveWorkspace(
            args = arrayOf("--goal", "x", "--workspace", flagDir.path),
            config = config(workspace = configDir.path),
        )

        assertEquals(flagDir.absoluteFile.path, resolved.baseDirectory)
    }

    @Test
    fun `the short flag is accepted`(@TempDir flagDir: File) {
        val resolved = resolveWorkspace(args = arrayOf("-w", flagDir.path), config = null)

        assertEquals(flagDir.absoluteFile.path, resolved.baseDirectory)
    }

    @Test
    fun `the config file's workspace key is used when no flag is given`(@TempDir configDir: File) {
        val resolved = resolveWorkspace(args = arrayOf("--goal", "x"), config = config(workspace = configDir.path))

        assertEquals(configDir.absoluteFile.path, resolved.baseDirectory)
    }

    @Test
    fun `with neither flag nor config the CLI's own working directory is the workspace`() {
        val resolved = resolveWorkspace(args = emptyArray(), config = config(workspace = null))

        assertEquals(File(System.getProperty("user.dir")).absoluteFile.path, resolved.baseDirectory)
    }
}
