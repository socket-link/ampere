package link.socket.ampere.renderer

object SparkNameFormatter {
    private const val PHASE_PREFIX = "Phase:"

    fun format(name: String): String {
        if (!name.startsWith(PHASE_PREFIX)) {
            return name
        }

        val phase = name.removePrefix(PHASE_PREFIX).trim()
        return if (phase.isBlank()) {
            "[Phase]"
        } else {
            "[Phase] $phase"
        }
    }
}
