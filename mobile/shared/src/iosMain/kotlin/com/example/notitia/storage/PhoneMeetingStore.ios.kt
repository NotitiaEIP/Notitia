package com.example.notitia.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

// Application Support is backed up to iCloud by default: the folder opts out (product rule 3).
@OptIn(ExperimentalForeignApi::class)
fun phoneMeetingStore(): MeetingStore {
    val files = NSFileManager.defaultManager
    val support = checkNotNull(
        files.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, appropriateForURL = null, create = true, error = null),
    )
    val directory = checkNotNull(support.URLByAppendingPathComponent("meetings", isDirectory = true))
    files.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)
    directory.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    return FileMeetingStore(Path(checkNotNull(directory.path)))
}
