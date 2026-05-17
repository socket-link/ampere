package link.socket.ampere.agents.domain.cognition.sparks

internal actual suspend fun loadBundledSparkFallback(
    resourcePath: String,
    fallbackPath: String,
): String? {
    val candidatePaths = listOf(
        resourcePath,
        fallbackPath,
        resourcePath.removePrefix("/"),
        fallbackPath.removePrefix("/"),
        resourcePath.substringAfterLast('/'),
        fallbackPath.substringAfterLast('/'),
    ).distinct()

    val classLoaders = listOfNotNull(
        Thread.currentThread().contextClassLoader,
        DefaultPhaseSparkLibrary::class.java.classLoader,
    )

    for (classLoader in classLoaders) {
        for (candidate in candidatePaths) {
            classLoader.getResourceAsStream(candidate)?.use { stream ->
                return stream.readBytes().decodeToString()
            }
        }
    }

    return null
}
