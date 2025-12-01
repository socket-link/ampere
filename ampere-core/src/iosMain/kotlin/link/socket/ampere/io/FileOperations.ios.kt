package link.socket.ampere.io

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.darwin.NSObject
import platform.darwin.NSObjectMeta

private class BundleMarker : NSObject() {
    companion object : NSObjectMeta()
}

@OptIn(BetaInteropApi::class)
val bundle by lazy {
    NSBundle.bundleForClass(BundleMarker)
}

actual fun readFolderContents(folderPath: String): Result<List<String>> =
    Result.failure(
        UnsupportedOperationException(),
    )

actual fun createFile(
    folderPath: String,
    fileName: String,
    fileContent: String,
): Result<String> = Result.failure(UnsupportedOperationException())

actual fun parseCsv(
    folderPath: String,
    fileName: String,
): Result<List<List<String>>> = Result.failure(UnsupportedOperationException())

@OptIn(ExperimentalForeignApi::class)
actual fun readFile(filePath: String): Result<String> {
    val (filename, type) =
        when (val lastPeriodIndex = filePath.lastIndexOf('.')) {
            0 -> {
                null to filePath.drop(1)
            }

            in 1..Int.MAX_VALUE -> {
                filePath.take(lastPeriodIndex) to filePath.drop(lastPeriodIndex + 1)
            }

            else -> {
                filePath to null
            }
        }
    val path =
        bundle.pathForResource(filename, type) ?: error(
            "Couldn't get path of $filePath (parsed as: ${
                listOfNotNull(
                    filename,
                    type,
                ).joinToString(".")
            })",
        )

    return memScoped {
        NSString.stringWithContentsOfFile(
            path = path,
            encoding = NSUTF8StringEncoding,
            error = null,
        )?.let { contents ->
            Result.success(contents)
        } ?: Result.failure(
            RuntimeException("Couldn't load resource: $filePath"),
        )
    }
}
