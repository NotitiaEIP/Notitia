package com.example.notitia.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val VERSION: Byte = 1
private const val IV_BYTES = 12
private const val TAG_BITS = 128
private const val ENVELOPE_BYTES = 1 + IV_BYTES + KEY_BYTES + TAG_BITS / 8
private const val SEALING = "AES/GCM/NoPadding"

// The key of the app, which the core seals the meetings with (docs/adr/0007-cle-de-l-app.md): 32 random bytes drawn
// once, kept in a file wrapped by an AES key of the Keystore that never leaves the phone. Version 1 of the file:
// the version, the IV, then the key sealed with the version as authenticated data. Never written over: a key lost is
// every audio lost.
class MeetingsKey internal constructor(
    private val file: File,
    private val wrapping: Wrapping,
    private val syncFolder: (File) -> Unit,
) {
    constructor(context: Context) : this(File(context.filesDir, "meetings.key"), KeystoreWrapping, ::syncFolder)

    private val unfinished = File(file.path + ".tmp")

    // Read off the main thread: the Keystore can take its time. null when no key can be had now: the app then
    // records nothing, and asks again at its next launch.
    fun read(): ByteArray? = try {
        // A write cut short: its key was never handed out.
        unfinished.delete()
        when {
            !file.exists() -> drawn(wrapping.existing() ?: wrapping.made())
            // Read only at its size: a file that grew is not ours, and is never loaded whole.
            file.length() == ENVELOPE_BYTES.toLong() -> opened(file.readBytes())
            else -> null
        }
    } catch (_: Exception) {
        // A Keystore that fails now, as some makers' do, or a disk that refuses: it may pass by the next launch,
        // and nothing was written over.
        null
    }

    private fun opened(envelope: ByteArray): ByteArray? {
        if (envelope.size != ENVELOPE_BYTES || envelope[0] != VERSION) return null
        val wrapper = wrapping.existing() ?: return replaced()
        val cipher = Cipher.getInstance(SEALING)
        cipher.init(Cipher.DECRYPT_MODE, wrapper, GCMParameterSpec(TAG_BITS, envelope, 1, IV_BYTES))
        cipher.updateAAD(envelope, 0, 1)
        return try {
            cipher.doFinal(envelope, 1 + IV_BYTES, envelope.size - 1 - IV_BYTES)
        } catch (_: AEADBadTagException) {
            // The Keystore's answer, not a failure of the moment: damaged, or sealed under another key, this envelope
            // never opens. Left so, it would keep the phone from recording until the app is installed again.
            replaced()
        }
    }

    // This key is gone for good, with the key that wrapped it or with its envelope: the envelope is kept aside, never
    // written over, and a new key is drawn under a new wrapping key. Audios sealed with the old key stay unreadable,
    // new ones are recorded.
    private fun replaced(): ByteArray? {
        val aside = File("${file.path}.lost-${System.currentTimeMillis()}")
        return if (file.renameTo(aside)) drawn(wrapping.made()) else null
    }

    private fun drawn(wrapper: SecretKey): ByteArray? {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(SEALING).apply { init(Cipher.ENCRYPT_MODE, wrapper) }
        val version = byteArrayOf(VERSION)
        cipher.updateAAD(version)
        val envelope = version + cipher.iv + cipher.doFinal(key)
        try {
            kept(envelope)
        } catch (failure: Exception) {
            key.fill(0)
            throw failure
        }
        return key
    }

    // On the disk before the key is used: a key whose envelope a power cut loses would seal audios nobody can open.
    private fun kept(envelope: ByteArray) {
        check(envelope.size == ENVELOPE_BYTES && !file.exists())
        try {
            FileOutputStream(unfinished).use {
                it.write(envelope)
                it.fd.sync()
            }
            if (!unfinished.renameTo(file)) throw IOException("the key was not kept")
        } finally {
            unfinished.delete()
        }
        syncFolder(file.absoluteFile.parentFile ?: throw IOException("the key has no folder"))
    }
}

// The key of the Keystore that wraps the app's. The tests give one of their own.
internal interface Wrapping {
    // null when the Keystore no longer has it, or holds it invalid for good: what it wrapped can never be opened again.
    fun existing(): SecretKey?

    // A new one, in place of any other.
    fun made(): SecretKey
}

private object KeystoreWrapping : Wrapping {
    private const val ALIAS = "notitia-meetings"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun existing(): SecretKey? {
        val key = store().getKey(ALIAS, null) as SecretKey? ?: return null
        return try {
            Cipher.getInstance(SEALING).init(Cipher.ENCRYPT_MODE, key)
            key
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        }
    }

    // In the trusted environment of the phone, not StrongBox, which would slow every launch. No unlocked screen and
    // no user authentication asked: a meeting goes on recording with the screen locked (US-11).
    override fun made(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        store().deleteEntry(ALIAS)
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        init(
            KeyGenParameterSpec.Builder(ALIAS, purposes)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }
}

// A renamed file is lost to a power cut until its folder reaches the disk too.
private fun syncFolder(folder: File) {
    val descriptor = Os.open(folder.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}
