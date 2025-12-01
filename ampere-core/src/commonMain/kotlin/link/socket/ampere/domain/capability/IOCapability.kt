package link.socket.ampere.domain.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import link.socket.ampere.domain.tool.FunctionProvider
import link.socket.ampere.domain.tool.ParameterDefinition
import link.socket.ampere.io.createFile
import link.socket.ampere.io.parseCsv
import link.socket.ampere.io.readFile
import link.socket.ampere.io.readFolderContents
import link.socket.ampere.util.logWith

sealed class IOCapability(open val agentTag: String) : Capability {

    override val tag: String
        get() = "$agentTag-IO${super.tag}"

    data class ReadFolderContents(override val agentTag: String) : IOCapability(agentTag) {
        override val impl: Pair<String, FunctionProvider> =
            FunctionProvider.provide(
                name = "readFolderContents",
                description = """
                    Reads a folder's contents on the local disk with the given path, and returns the list of files and folders after executing.
                    The paths where the folder should be read from *must* be a relative path from the User's home directory.
                """.trimIndent(),
                function = { args: JsonObject ->
                    val folderPath = args.getValue("folderPath").jsonPrimitive.content
                    readFolderContentsImpl(folderPath)
                },
                parameterList = listOf(
                    ParameterDefinition(
                        name = "folderPath",
                        isRequired = true,
                        definition = buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                """
                                    The path where the folder contents should be read from.
                                    This path *must* include the entire path relative to the User's home directory.
                                """.trimIndent(),
                            )
                        },
                    ),
                ),
            )

        /*
         * Creates a file on the local disk with the given name and content, and returns the status of the file
         * creation after executing.
         *
         * @param folderPath the path where the file should be created, relative to the user's home directory
         */
        private fun readFolderContentsImpl(folderPath: String): String {
            logWith(tag).i("\nArgs:\nfolderPath=$folderPath")

            return readFolderContents(folderPath)
                .map { contents -> contents.joinToString("\n") }
                .getOrNull()
                ?: "Error: Failed to read folder contents".also { response ->
                    logWith(tag).i("\nResponse:\n$response")
                }
        }
    }

    data class CreateFile(override val agentTag: String) : IOCapability(agentTag) {
        override val impl: Pair<String, FunctionProvider> =
            FunctionProvider.provide(
                name = "createFile",
                description = """
                    Creates a file on the local disk with the given name and content, and returns the status of the file creation after executing."
                    The path where the file should be created *must* be a relative path from the User's home directory.
                    This function can be ran in parallel.
                """.trimIndent(),
                function = { args: JsonObject ->
                    val folderPath = args.getValue("folderPath").jsonPrimitive.content
                    val fileName = args.getValue("fileName").jsonPrimitive.content
                    val fileContent = args.getValue("fileContent").jsonPrimitive.content
                    createFileImpl(folderPath, fileName, fileContent)
                },
                parameterList = listOf(
                    ParameterDefinition(
                        name = "folderPath",
                        isRequired = true,
                        definition = buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                """
                                    The path where the file should be created
                                    This path *must* include the entire path relative to the User's home directory.
                                """.trimIndent(),
                            )
                        },
                    ),
                    ParameterDefinition(
                        name = "fileName",
                        isRequired = true,
                        definition = buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                """
                                    The name of the file to create.
                                    This name *must* include the file's extension.
                                """.trimIndent(),
                            )
                        },
                    ),
                    ParameterDefinition(
                        name = "fileContent",
                        isRequired = true,
                        definition = buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                """
                                    The content that the newly created file should contain.
                                """.trimIndent(),
                            )
                        },
                    ),
                ),
            )

        /*
         * Creates a file on the local disk with the given name and content, and returns the status of the file
         * creation after executing.
         *
         * @param folderPath the path where the file should be created, relative to the user's home directory
         * @param fileName the name of the file to create
         * @param fileContent the content that the new file should contain
         */
        private fun createFileImpl(
            folderPath: String,
            fileName: String,
            fileContent: String,
        ): String {
            logWith(tag).i("\nArgs:\nfolderPath=$folderPath\nfileName=$fileName\nfileContent=$fileContent")
            return createFile(folderPath, fileName, fileContent).getOrNull()
                ?: "Error: Failed to create file".also { response ->
                    logWith(tag).i("\nResponse:\n$response")
                }
        }
    }

    data class ReadFiles(override val agentTag: String) : IOCapability(agentTag) {
        override val impl: Pair<String, FunctionProvider> =
            FunctionProvider.provide(
                name = "readFiles",
                description = """
                    Reads a set of files on the local disk with the given path and name, and returns the combined contents of the files after executing.
                    You must always prefer to send multiple file paths in one call rather than making separate calls to this function with only one path each, as it is more efficient.
                    The paths where the files should be read from *must* be a relative path from the User's home directory.
                    Multiple file paths should _not_ be sent as an array, and should be joined together as a string separated by commas.
                    This function *cannot* be ran in parallel.
                """.trimIndent(),
                function = { args: JsonObject ->
                    val filePaths = args.getValue("filePaths").jsonPrimitive.content.split(",")
                    readFilesImpl(filePaths)
                },
                parameterList = listOf(
                    ParameterDefinition(
                        name = "filePaths",
                        isRequired = true,
                        definition = buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                """
                                    The paths where the files should be read from, where each one *must* be a relative path from the User's home directory.
                                    These must be valid paths to the files you want to read, don't send any parameter that does not resemble a file path.
                                    Multiple file paths should be joined together and separated by commas.
                                """.trimIndent(),
                            )
                        },
                    ),
                ),
            )

        /*
         * Reads a file on the local disk with the given name, and returns the content of the file after executing.
         *
         * @param folderPath the path where the file should be read from, relative to the user's home directory
         * @param fileName the name of the file to read
         */
        private fun readFilesImpl(filePaths: List<String>): String {
            logWith(tag).i("Args:\nfilePaths=$filePaths")

            return filePaths.joinToString("\n\n") { filePath ->
                val fileContent = readFile(filePath)

                """
                    File: $filePath
                    Content: ${fileContent.getOrNull() ?: "Error: Failed to read file"}
                """.trimIndent()
            }.also { response ->
                logWith(tag).i("\nResponse:\n$response")
            }
        }
    }

    data class ParseCsv(override val agentTag: String) : IOCapability(agentTag) {
        override val impl: Pair<String, FunctionProvider> =
            FunctionProvider.provideCSV(
                name = "parseCsv",
                description = """
                    Reads a CSV file on the local disk with the given name, and returns the table of strings after executing.
                    This path *must* include the entire path relative to the User's home directory.
                """.trimIndent(),
                function = { args: JsonObject ->
                    val folderPath = args.getValue("folderPath").jsonPrimitive.content
                    val fileName = args.getValue("fileName").jsonPrimitive.content
                    parseCsvImpl(folderPath, fileName)
                },
                parameterList = listOf(
                    ParameterDefinition(
                        name = "folderPath",
                        isRequired = true,
                        definition = buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                """
                                    The paths where the file should be read from.
                                    This path *must* include the entire path relative to the User's home directory.
                                """.trimIndent(),
                            )
                        },
                    ),
                    ParameterDefinition(
                        name = "fileName",
                        isRequired = true,
                        definition = buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                """
                                    The name of the file to read.
                                    This name *must* include the file's extension.
                                """.trimIndent(),
                            )
                        },
                    ),
                ),
            )

        private fun parseCsvImpl(
            folderPath: String,
            fileName: String,
        ): List<List<String>> {
            logWith(tag).i("\nArgs:\nfolderPath=$folderPath\nfileName=$fileName")

            return parseCsv(folderPath, fileName)
                .getOrNull()
                ?: listOf(listOf("Error: Failed to parse CSV")).also {
                    logWith(tag).i("\nResponse:\n$it")
                }
        }
    }
}
