package com.example.notitia.bridge

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

// For RustCore.swift, which hands the core Data and gets Data back. Swift reads a Kotlin ByteArray one byte per call:
// these copy it whole, once.

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun data(bytes: ByteArray): NSData {
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
fun bytes(data: NSData): ByteArray {
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes
}
