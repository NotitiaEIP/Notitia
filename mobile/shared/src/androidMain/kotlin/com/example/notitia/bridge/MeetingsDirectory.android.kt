package com.example.notitia.bridge

import android.content.Context
import java.io.File

// The app's private folder: no other app reads it, and the backup rules of the manifest keep it on the phone.
fun meetingsDirectory(context: Context): String = File(context.filesDir, "meetings").path
