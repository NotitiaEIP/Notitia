import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.example.notitia.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(project(":coreAndroid"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.navigationevent.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // The Android flavour of JNA only knows phones: on the host, RustCoreTest needs the one for computers.
        getByName("androidHostTest").dependencies {
            implementation(libs.jna)
        }
    }
}

// The core built for this machine, which RustCoreTest calls.
val hostCore: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
    hostCore(project(":coreAndroid", "hostCore"))
}

// ScreensBoundaryTest reads these sources, BridgeContractTest the examples of the core, and RustCoreTest calls the
// core itself: a change in any of them must be enough to run the tests again.
tasks.withType<Test>().configureEach {
    val core: FileCollection = hostCore
    inputs.dir("src/commonMain/kotlin").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("../../core/tests/fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(core).withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Djna.library.path=${core.singleFile.path}") })
}