package com.example.notitia

import android.util.Log
import com.example.notitia.bridge.CoreSelfCheck
import com.example.notitia.bridge.NotitiaCore
import kotlin.concurrent.thread

// Every debug launch proves the bridge on the device it runs on, until the storage runs on the core (T-57).
// The marker is fixed words: no text of a meeting reaches the log.
fun checkCoreForBuild(core: NotitiaCore) {
    thread(name = "core-self-check") { Log.i("NotitiaCore", CoreSelfCheck(core).run()) }
}
