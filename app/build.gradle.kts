import java.io.File
import java.nio.file.Files
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release 2.3.0 of dawn-used-yeet-alt/hotaudio-extractor. Pinned so bindings
// and the native library cannot drift apart between CI runs.
val hotaudioExtractorRef = "v2.3.0"

// Only arm64 by default: it halves the Rust build time and removes the unused
// x86_64 native payload. Override with -Photaudio.abis=arm64-v8a,x86_64 for
// emulator builds.
val hotaudioAbis = (providers.gradleProperty("hotaudio.abis").orNull ?: "arm64-v8a")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

val releaseKeystore = file(
    System.getenv("DAWNGASM_KEYSTORE_PATH") ?: "release.jks"
)
val hasReleaseSigning = releaseKeystore.exists() &&
    !System.getenv("DAWNGASM_KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("DAWNGASM_KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "dev.dawngasm.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dawngasm.app"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += hotaudioAbis
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("DAWNGASM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DAWNGASM_KEY_ALIAS") ?: "dawngasm"
                keyPassword = System.getenv("DAWNGASM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
        // The root crate is also built as a cdylib by cargo, but the FFI
        // library statically contains it. Never ship the duplicate ~5 MB lib.
        jniLibs.excludes += setOf("**/libhotaudio.so")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(libs.rustls.platform.verifier)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

abstract class GenerateHotaudioBindingsTask : DefaultTask() {
    @get:Input
    abstract val extractorRef: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val ref = extractorRef.get()
        val outDir = outputDir.get().asFile
        val outputFile = File(outDir, "hotaudio_ffi.kt")
        val markerFile = File(outDir, ".hotaudio-bindings-ref")
        if (outputFile.exists() && markerFile.exists() && markerFile.readText().trim() == ref) return

        val hasCargo = try {
            ProcessBuilder("cargo", "--version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
        if (!hasCargo) {
            throw GradleException(
                "cargo is required to generate hotaudio UniFFI bindings; install Rust or commit the generated bindings",
            )
        }

        val tempDir = Files.createTempDirectory("hotaudio-extractor").toFile()
        try {
            println("Cloning hotaudio-extractor $ref to generate UniFFI bindings...")
            val cloneProc = ProcessBuilder(
                "git", "clone", "--depth", "1", "--branch", ref,
                "https://github.com/dawn-used-yeet-alt/hotaudio-extractor.git",
                tempDir.absolutePath,
            )
                .inheritIO()
                .start()
            if (cloneProc.waitFor() != 0) {
                throw GradleException("Failed to clone hotaudio-extractor")
            }

            println("Building bindings with scripts/build-bindings.sh...")
            val pb = ProcessBuilder("bash", "scripts/build-bindings.sh", "--release")
                .directory(tempDir)
                .redirectErrorStream(true)
            val buildProc = pb.start()
            buildProc.inputStream.bufferedReader().forEachLine { println(it) }
            val exit = buildProc.waitFor()
            if (exit != 0) {
                throw GradleException("Failed to build bindings (exit $exit)")
            }

            val generated = File(tempDir, "bindings/out/net/hotaudio/extractor/hotaudio_ffi.kt")
            if (generated.exists()) {
                outDir.mkdirs()
                generated.copyTo(outputFile, overwrite = true)
                println("Successfully generated ${outputFile.absolutePath}")
            } else {
                throw GradleException("Failed to locate generated hotaudio_ffi.kt at ${generated.absolutePath}")
            }

            val tlsFile = File(tempDir, "bindings/android/HotaudioTls.kt")
            val targetTls = File(outDir, "HotaudioTls.kt")
            if (tlsFile.exists() && !targetTls.exists()) {
                tlsFile.copyTo(targetTls, overwrite = true)
            }
            markerFile.writeText(ref)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

abstract class BuildHotaudioNativeTask : DefaultTask() {
    @get:Input
    abstract val extractorRef: Property<String>

    @get:OutputDirectory
    abstract val jniDir: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @TaskAction
    fun buildNative() {
        val destDir = jniDir.get().asFile
        val abisToBuild = abis.get()
        val ref = extractorRef.get()
        val expected = abisToBuild.map { File(destDir, "$it/libhotaudio_ffi.so") }

        // Remove the core crate's cdylib; the FFI library already contains it.
        abisToBuild.forEach { abi ->
            File(destDir, "$abi/libhotaudio.so").delete()
        }

        if (expected.all { it.exists() }) return

        val hasCargo = try {
            ProcessBuilder("cargo", "--version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
        if (!hasCargo) {
            throw GradleException(
                "cargo is required to build libhotaudio_ffi.so; install Rust or commit prebuilt jniLibs",
            )
        }

        val tempDir = Files.createTempDirectory("hotaudio-native").toFile()
        try {
            println("Cloning hotaudio-extractor $ref for native build...")
            val cloneProc = ProcessBuilder(
                "git", "clone", "--depth", "1", "--branch", ref,
                "https://github.com/dawn-used-yeet-alt/hotaudio-extractor.git",
                tempDir.absolutePath,
            )
                .inheritIO()
                .start()
            if (cloneProc.waitFor() != 0) {
                throw GradleException("Failed to clone hotaudio-extractor")
            }

            val hasCargoNdk = try {
                ProcessBuilder("cargo", "ndk", "--version").start().waitFor() == 0
            } catch (e: Exception) {
                false
            }
            if (!hasCargoNdk) {
                println("Installing cargo-ndk...")
                val installProc = ProcessBuilder("cargo", "install", "cargo-ndk", "--locked").inheritIO().start()
                if (installProc.waitFor() != 0) {
                    throw GradleException("cargo install cargo-ndk failed")
                }
            }

            val rustTargets = abisToBuild.mapNotNull {
                when (it) {
                    "arm64-v8a" -> "aarch64-linux-android"
                    "x86_64" -> "x86_64-linux-android"
                    "armeabi-v7a" -> "armv7-linux-androideabi"
                    else -> null
                }
            }
            if (rustTargets.isNotEmpty()) {
                ProcessBuilder("rustup", "target", "add")
                    .apply { command().addAll(rustTargets) }
                    .inheritIO()
                    .start()
                    .waitFor()
            }

            val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            val ndkDir = System.getenv("ANDROID_NDK_HOME")?.let { File(it) }
                ?: if (androidHome != null) {
                    val ndkParent = File(androidHome, "ndk")
                    if (ndkParent.exists()) {
                        ndkParent.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
                    } else null
                } else null

            val cmd = mutableListOf(
                "cargo", "ndk",
                "--platform", "26",
            )
            abisToBuild.forEach { abi ->
                cmd += listOf("-t", abi)
            }
            cmd += listOf(
                "-o", destDir.absolutePath,
                "build",
                "--manifest-path", "Cargo.toml",
                "--release",
                "-p", "hotaudio-ffi",
            )

            println("Building libhotaudio_ffi.so for $abisToBuild: ${cmd.joinToString(" ")}")
            val bindingsDir = File(tempDir, "bindings")
            val pb = ProcessBuilder(cmd)
                .directory(bindingsDir)
                .redirectErrorStream(true)
            if (ndkDir != null && ndkDir.exists()) {
                println("Using NDK from: ${ndkDir.absolutePath}")
                pb.environment()["ANDROID_NDK_HOME"] = ndkDir.absolutePath
                pb.environment()["NDK_HOME"] = ndkDir.absolutePath
            }
            val buildProc = pb.start()
            buildProc.inputStream.bufferedReader().forEachLine { println(it) }
            val exit = buildProc.waitFor()
            if (exit != 0) {
                throw GradleException("cargo ndk exited with code $exit")
            }
            abisToBuild.forEach { abi ->
                File(destDir, "$abi/libhotaudio.so").delete()
            }
            if (expected.any { !it.exists() }) {
                throw GradleException(
                    "Expected native libraries were not generated: " +
                        expected.filter { !it.exists() }.joinToString { it.absolutePath },
                )
            }
            println("Successfully built native libraries in ${destDir.absolutePath}")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

val generateHotaudioBindings = tasks.register<GenerateHotaudioBindingsTask>("generateHotaudioBindings") {
    extractorRef.set(hotaudioExtractorRef)
    outputDir.set(layout.projectDirectory.dir("src/main/java/net/hotaudio/extractor"))
}

val buildHotaudioNative = tasks.register<BuildHotaudioNativeTask>("buildHotaudioNative") {
    dependsOn(generateHotaudioBindings)
    extractorRef.set(hotaudioExtractorRef)
    jniDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
    abis.set(hotaudioAbis)
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(generateHotaudioBindings)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildHotaudioNative)
}
