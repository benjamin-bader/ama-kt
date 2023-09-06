package com.bendb.ama.app

actual object FileSystems {
    actual val DEFAULT: okio.FileSystem = okio.FileSystem.SYSTEM
}
