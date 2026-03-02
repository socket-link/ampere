package link.socket.ampere.domain.ai.pricing

internal actual suspend fun loadBundledProviderPricingFallback(
    resourcePath: String,
    fallbackPath: String,
): String? = readBundledProviderPricingFromClasspath(
    resourcePath = resourcePath,
    fallbackPath = fallbackPath,
)

internal fun readBundledProviderPricingFromClasspath(
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
        BundledProviderPricingCatalog::class.java.classLoader,
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
