package link.socket.ampere.knowledge

import kotlinx.serialization.Serializable

/**
 * A named scope tag that partitions [KnowledgeDocument]s for permission gating
 * and filtered retrieval.
 *
 * Plugins request `knowledge.query(text, scope)` and the on-device
 * [KnowledgeStore] only surfaces chunks whose document is tagged with at least
 * one of the requested scopes.
 * [PluginPermission.KnowledgeQuery][link.socket.ampere.plugin.permission.PluginPermission.KnowledgeQuery]
 * gates which scope strings a plugin may even request.
 *
 * Scopes are open-ended strings — platform import pipelines own which scopes
 * apply to which documents. The [companion object][Companion] exposes the
 * well-known names the AMPERE default plugin tool registry references.
 *
 * Documents persisted before the W2.3 scope schema landed have no scope rows.
 * They are returned for unfiltered queries (matching W0.5 behaviour) and
 * excluded from any scope-filtered query.
 */
@Serializable
data class KnowledgeScope(val name: String) {

    init {
        require(name.isNotBlank()) { "KnowledgeScope name must not be blank" }
        require(name.trim() == name) {
            "KnowledgeScope name must not have leading/trailing whitespace"
        }
    }

    companion object {

        /** Personal documents: notes, journals, private correspondence. */
        val Personal: KnowledgeScope = KnowledgeScope("personal")

        /** Work documents: meeting notes, design docs, internal references. */
        val Work: KnowledgeScope = KnowledgeScope("work")

        /** Reading material: imported articles, book excerpts, web clippings. */
        val Reading: KnowledgeScope = KnowledgeScope("reading")
    }
}
