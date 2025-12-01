package link.socket.ampere.io

// Expected function to read a folder's contents located at the given folder path.
expect fun readFolderContents(folderPath: String): Result<List<String>>

// Expected function to create a file with the given folder path, file name, and file content.
expect fun createFile(
    folderPath: String,
    fileName: String,
    fileContent: String,
): Result<String>

// Expected function to parse a CSV file located at the given folder path and file name.
expect fun parseCsv(
    folderPath: String,
    fileName: String,
): Result<List<List<String>>>

// Expected function to read a file located at the given folder path and file name.
expect fun readFile(filePath: String): Result<String>
