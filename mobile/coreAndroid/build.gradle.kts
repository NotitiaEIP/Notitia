import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
}

// The Rust core for Android: its libraries, and the Kotlin that UniFFI generates to call them. Nothing here is
// written by hand, and nothing generated is committed.
abstract class CargoTask : DefaultTask() {
    @get:Internal
    abstract val coreDirectory: DirectoryProperty

    @get:Internal
    abstract val cargo: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    protected fun cargo(environment: Map<String, String>, vararg arguments: String) {
        try {
            exec.exec {
                workingDir = coreDirectory.get().asFile
                environment(environment)
                commandLine(cargo.get(), *arguments)
            }
        } catch (failure: Exception) {
            throw GradleException(
                "The Rust core did not build: the error above says which step failed. " +
                    "`./notitia doctor` says what this machine lacks to build it.",
                failure,
            )
        }
    }
}

abstract class BuildCoreLibraries : CargoTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    // The path only: the NDK is two gigabytes that Gradle has no reason to fingerprint.
    @get:Input
    abstract val ndk: Property<String>

    @get:OutputDirectory
    abstract val libraries: DirectoryProperty

    // The phone and the emulator. Always optimised: a debug build of the core is too slow to record with.
    @TaskAction
    fun build() = cargo(
        mapOf("ANDROID_NDK_HOME" to ndk.get()),
        "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-o", libraries.get().asFile.path, "build", "--release",
    )
}

abstract class GenerateCoreBindings : CargoTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generator: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val kotlin: DirectoryProperty

    // Read from the built library, so the Kotlin always matches the code it calls.
    @TaskAction
    fun generate() = cargo(
        emptyMap(),
        "run", "--quiet", "-p", "uniffi-bindgen", "--", "generate",
        libraries.file("arm64-v8a/libnotitia_core.so").get().asFile.path,
        "--language", "kotlin", "--no-format", "--out-dir", kotlin.get().asFile.path,
    )
}

val core = rootProject.layout.projectDirectory.dir("../core")

// Android Studio starts Gradle without the PATH of a terminal: look first where rustup installs.
val cargoPath = providers.environmentVariable("CARGO_HOME")
    .orElse(providers.systemProperty("user.home").map { "$it/.cargo" })
    .map { home -> listOf("cargo", "cargo.exe").map { File(home, "bin/$it") }.firstOrNull { it.exists() }?.path ?: "cargo" }

val buildCoreLibraries = tasks.register<BuildCoreLibraries>("buildCoreLibraries") {
    coreDirectory = core
    cargo = cargoPath
    sources.from(core.asFileTree.matching { include("src/**", "Cargo.toml", "Cargo.lock", "rust-toolchain.toml") })
    ndk = androidComponents.sdkComponents.ndkDirectory.map { it.asFile.path }
}

val generateCoreBindings = tasks.register<GenerateCoreBindings>("generateCoreBindings") {
    coreDirectory = core
    cargo = cargoPath
    libraries = buildCoreLibraries.flatMap { it.libraries }
    generator.from(core.asFileTree.matching { include("uniffi.toml", "uniffi-bindgen/**") })
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildCoreLibraries, BuildCoreLibraries::libraries)
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateCoreBindings, GenerateCoreBindings::kotlin)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "com.example.notitia.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // From r28 on, the NDK aligns libraries on 16 KB pages, which the Play Store requires.
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // With the library rather than in the app: whoever shrinks the code gets the rules of the bridge with it.
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // api: the generated Kotlin shows JNA types in its signatures.
    api(variantOf(libs.jna) { artifactType("aar") })
}
