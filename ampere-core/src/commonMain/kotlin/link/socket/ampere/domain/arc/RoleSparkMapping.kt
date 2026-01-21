package link.socket.ampere.domain.arc

/**
 * Maps arc agent roles to their default spark identifiers.
 */
object RoleSparkMapping {
    private val mapping = mapOf(
        "pm" to "product-management",
        "code" to "software-engineer",
        "qa" to "quality-assurance",
        "planner" to "infrastructure-planner",
        "executor" to "infrastructure-executor",
        "monitor" to "infrastructure-monitor",
        "scholar" to "research-scholar",
        "writer" to "technical-writer",
        "critic" to "content-critic",
        "analyst" to "data-analyst",
        "engineer" to "data-engineer",
        "validator" to "data-validator",
        "scanner" to "security-scanner",
        "remediator" to "security-remediator",
        "researcher" to "content-researcher",
        "editor" to "content-editor",
    )

    /**
     * Returns the default spark for a role, if known.
     */
    fun getDefaultSpark(role: String): String? = mapping[role.lowercase()]

    /**
     * Returns default spark (when available) followed by additional sparks.
     */
    fun getAllSparks(role: String, additionalSparks: List<String>): List<String> {
        val defaultSpark = getDefaultSpark(role)
        return if (defaultSpark == null) {
            additionalSparks
        } else {
            listOf(defaultSpark) + additionalSparks
        }
    }
}
