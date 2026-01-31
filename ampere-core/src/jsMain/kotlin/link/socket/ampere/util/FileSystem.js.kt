package link.socket.ampere.util

import okio.FileSystem
import okio.fakefilesystem.FakeFileSystem

// JS/Browser doesn't have direct filesystem access
// Use a FakeFileSystem for compatibility - real file operations will fail gracefully
actual val systemFileSystem: FileSystem = FakeFileSystem()
