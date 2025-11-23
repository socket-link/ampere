package link.socket.ampere.io

import com.lordcodes.turtle.ShellLocation
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

actual fun readFolderContents(folderPath: String): Result<List<String>> {
    val workingDirPath = ShellLocation.HOME.resolve(folderPath).absolutePath
    val path = workingDirPath.toPath()
    val files = mutableListOf<String>()

    try {
        FileSystem.SYSTEM.list(path).forEach {
            files.add(it.name)
        }
    } catch (e: IOException) {
        return Result.failure(e)
    }

    return Result.success(files)
}

actual fun createFile(
    folderPath: String,
    fileName: String,
    fileContent: String,
): Result<String> {
    val workingDirPath = ShellLocation.HOME.resolve(folderPath).absolutePath
    val filePath = "$workingDirPath/$fileName".toPath()

    try {
        FileSystem.SYSTEM.write(filePath) {
            writeUtf8(fileContent)
        }
    } catch (e: IOException) {
        return Result.failure(e)
    }

    return Result.success("File created")
}

actual fun parseCsv(
    folderPath: String,
    fileName: String,
): Result<List<List<String>>> {
    val workingDirPath = ShellLocation.HOME.resolve(folderPath).absolutePath
    val filePath = "$workingDirPath/$fileName".toPath()
    val lines = mutableListOf<String>()

    try {
        FileSystem.SYSTEM.read(filePath) {
            while (true) {
                val line = readUtf8Line() ?: break
                lines.add(line)
            }
        }
    } catch (e: IOException) {
        return Result.failure(e)
    }

    val flattenedLines = lines.map { it.split(",") }
    return Result.success(flattenedLines)
}

actual fun readFile(filePath: String): Result<String> {
    val fileName = ShellLocation.HOME.resolve(filePath).absolutePath
    val fileNamePath = fileName.toPath()
    var result = ""

    try {
        FileSystem.SYSTEM.read(fileNamePath) {
            while (true) {
                val line = readUtf8Line() ?: break
                result += (line + "\n")
            }
        }
    } catch (e: IOException) {
        return Result.failure(e)
    }

    return Result.success(result)
}
