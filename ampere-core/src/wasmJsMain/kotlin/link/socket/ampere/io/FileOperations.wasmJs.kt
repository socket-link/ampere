package link.socket.ampere.io

/**
 * JS implementation of file operations.
 * Browser environments do not have direct filesystem access.
 * These operations return failures indicating the limitation.
 */

actual fun readFolderContents(folderPath: String): Result<List<String>> =
    Result.failure(
        UnsupportedOperationException("File system access is not supported in browser environments"),
    )

actual fun createFile(
    folderPath: String,
    fileName: String,
    fileContent: String,
): Result<String> =
    Result.failure(
        UnsupportedOperationException("File system access is not supported in browser environments"),
    )

actual fun parseCsv(
    folderPath: String,
    fileName: String,
): Result<List<List<String>>> =
    Result.failure(
        UnsupportedOperationException("File system access is not supported in browser environments"),
    )

actual fun readFile(filePath: String): Result<String> =
    Result.failure(
        UnsupportedOperationException("File system access is not supported in browser environments"),
    )
