package com.example.notitia.bridge

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

// Application Support is backed up to iCloud by default: the folder opts out (product rule 3).
@OptIn(ExperimentalForeignApi::class)
fun meetingsDirectory(): String {
    val files = NSFileManager.defaultManager
    val support = checkNotNull(
        files.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, appropriateForURL = null, create = true, error = null),
    )
    val directory = checkNotNull(support.URLByAppendingPathComponent("meetings", isDirectory = true))
    files.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)
    directory.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    return checkNotNull(directory.path)
}
