package com.example.notitia.storage

import android.content.Context
import kotlinx.io.files.Path

// The app's private folder: no other app reads it, and the backup rules of the manifest keep it on the phone.
fun phoneMeetingStore(context: Context): MeetingStore = FileMeetingStore(Path(context.filesDir.path, "meetings"))
