package link.socket.ampere.agents.domain.cognition.sparks

import java.io.File

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

    for (file in candidateFiles(resourcePath = resourcePath, fallbackPath = fallbackPath)) {
        if (file.isFile) {
            return file.readText()
        }
    }

    return null
}

private fun candidateFiles(
    resourcePath: String,
    fallbackPath: String,
): List<File> {
    val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    val roots = listOf(
        userDir,
        File(userDir, "ampere-core"),
    ).distinctBy { it.path }

    val generatedAssetPaths = listOf(
        "build/generated/assets/copyDebugComposeResourcesToAndroidAssets/$resourcePath",
        "build/generated/assets/copyReleaseComposeResourcesToAndroidAssets/$resourcePath",
    )

    val sourcePaths = listOf(
        "src/commonMain/composeResources/$fallbackPath",
        "build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources/$fallbackPath",
    )

    return roots
        .flatMap { root ->
            (generatedAssetPaths + sourcePaths).map { relativePath ->
                File(root, relativePath)
            }
        }
        .distinctBy { it.path }
}
