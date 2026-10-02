package link.socket.ampere.roster

import kotlinx.serialization.Serializable

/**
 * A reference to one version of a role prompt (AMPR-379).
 *
 * Role prompts are versioned artifacts: the domain boundary of a roster lives in
 * them (what a "part" or a "finish" is for a Blueprint is prompt text, never a
 * type), so a change to a prompt is a change to what the roster *is*, and must be
 * visible as a version bump rather than a silent edit. A [RoleConfig] carries the
 * reference; [RosterPrompts] resolves it to text.
 *
 * Nothing in the repo versioned a prompt before this: bundled `AgentDefinition`s
 * inline their prompt as a string, and the `.spark.md` files are keyed by id only.
 */
@Serializable
data class PromptRef(
    val id: String,
    val version: Int,
) {
    /** The stable `id@vN` form, for logs and trace detail maps. */
    val key: String get() = "$id@v$version"
}
