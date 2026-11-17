package com.example.notitia

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The screens reach the meetings through a few interfaces only, so what holds them, the Rust core today, can change
// without a screen changing (ADR 0002). On the host, because only there can a test read the sources.
class ScreensBoundaryTest {
    private val sources = File("src/commonMain/kotlin/com/example/notitia")
    private val logic = Regex("""import com\.example\.notitia\.(bridge|recording|ai)\..+""")

    @Test
    fun noScreenImportsTheLogicBehindTheInterfaces() {
        val screens = sources.resolve("ui").walkTopDown().filter { it.extension == "kt" }.toList() + sources.resolve("App.kt")
        // Moved sources would make the check pass on nothing.
        assertTrue(screens.size > 1 && screens.all { it.isFile })
        val crossings = screens.flatMap { file -> file.readLines().filter(logic::matches).map { "${file.name}: $it" } }
        assertEquals(emptyList(), crossings)
    }
}
