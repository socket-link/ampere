package link.socket.ampere.util

import okio.FileSystem

/**
 * Platform-specific system filesystem.
 * This is the default filesystem for file operations.
 */
expect val systemFileSystem: FileSystem
