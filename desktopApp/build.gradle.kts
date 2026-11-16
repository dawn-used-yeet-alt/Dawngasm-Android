import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("hotaudio/bindings"))
}

dependencies {
    implementation(project(":composeApp"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.jna)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "dev.dawngasm.app.desktop.MainKt"

        // macOS reads the app-menu name from this property, not the window title.
        jvmArgs("-Dapple.awt.application.name=Dawngasm")

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Dawngasm"
            packageVersion = "2.0.0"
            description = "Dawngasm desktop"
            vendor = "Dawngasm"
            copyright = "© 2026 Dawngasm contributors"
            licenseFile.set(rootProject.file("LICENSE"))

            // The native clearance helper is staged here and shipped inside
            // every package's resources directory.
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))

            // VLCJ/JNA and Compose load a broad set of JDK classes at runtime.
            includeAllModules = true

            linux {
                packageName = "dawngasm"
                iconFile.set(project.file("icons/dawngasm.png"))
                debMaintainer = "Dawngasm contributors <noreply@dawngasm.app>"
                menuGroup = "Audio"
                appCategory = "Audio"
                appRelease = "1"
                rpmLicenseType = "GPL-3.0-or-later"
            }

            windows {
                iconFile.set(project.file("icons/dawngasm.ico"))
                menuGroup = "Dawngasm"
                upgradeUuid = "1bb6079d-f6b8-404d-8264-ce515c01525d"
                perUserInstall = true
                dirChooser = true
                console = false
            }

            macOS {
                iconFile.set(project.file("icons/dawngasm.icns"))
                bundleID = "dev.dawngasm.app"
                dockName = "Dawngasm"
                appCategory = "public.app-category.music"
                minimumSystemVersion = "12.0"
            }
        }
    }
}

val clearanceHelperDir = layout.projectDirectory.dir("native/clearance-helper")
val clearanceHelperBinary = if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
    "dawngasm-clearance-helper.exe"
} else {
    "dawngasm-clearance-helper"
}

val buildClearanceHelper by tasks.registering(Exec::class) {
    group = "desktop"
    description = "Builds the native clearance helper with Cargo."
    workingDir(clearanceHelperDir)
    commandLine("cargo", "build", "--release", "--locked")
    inputs.files(
        clearanceHelperDir.file("Cargo.toml"),
        clearanceHelperDir.file("Cargo.lock"),
    )
    inputs.dir(clearanceHelperDir.dir("src"))
    outputs.file(clearanceHelperDir.file("target/release/$clearanceHelperBinary"))
}

val stageClearanceHelper by tasks.registering(Copy::class) {
    group = "desktop"
    description = "Stages the native clearance helper for desktop packaging."
    dependsOn(buildClearanceHelper)
    from(clearanceHelperDir.file("target/release/$clearanceHelperBinary"))
    into(layout.buildDirectory.dir("native-resources/clearance-helper"))
    filePermissions {
        unix("rwxr-xr-x")
    }
}

tasks.matching {
    it.name == "createDistributable" ||
        it.name == "packageDeb" ||
        it.name == "packageRpm" ||
        it.name == "packageAppImage" ||
        it.name == "packageMsi" ||
        it.name == "packageDmg"
}.configureEach {
    dependsOn(stageClearanceHelper)
}

// --- hotaudio FFI ---------------------------------------------------------

val hotaudioExtractorRef = "v2.3.0"

val hotaudioPlatformKey = when {
    System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
    System.getProperty("os.name").contains("win", ignoreCase = true) -> "windows"
    else -> "linux"
}
val hotaudioArch = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64", "x64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}
val hotaudioPlatformDir = "$hotaudioPlatformKey-$hotaudioArch"
val hotaudioNativeLibrary = when (hotaudioPlatformKey) {
    "macos" -> "libhotaudio_ffi.dylib"
    "windows" -> "hotaudio_ffi.dll"
    else -> "libhotaudio_ffi.so"
}

val hotaudioBindingsDir = layout.buildDirectory.dir("hotaudio/bindings")
val hotaudioNativeDir = layout.buildDirectory.dir("native-resources/native/$hotaudioPlatformDir")

abstract class BuildDesktopHotaudioTask : DefaultTask() {
    @get:Input
    abstract val extractorRef: Property<String>

    @get:OutputDirectory
    abstract val bindingsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val nativeDir: DirectoryProperty

    @get:Input
    abstract val nativeLibraryName: Property<String>

    @TaskAction
    fun build() {
        val ref = extractorRef.get()
        val outBindings = bindingsDir.get().asFile
        val outNative = nativeDir.get().asFile
        val libraryName = nativeLibraryName.get()
        val bindingFile = File(outBindings, "hotaudio_ffi.kt")
        val markerFile = File(outBindings, ".hotaudio-bindings-ref")
        if (
            bindingFile.exists() &&
            markerFile.exists() &&
            markerFile.readText().trim() == ref &&
            File(outNative, libraryName).exists()
        ) {
            return
        }

        val tempDir = Files.createTempDirectory("dawngasm-hotaudio").toFile()
        try {
            run(
                tempDir,
                "git", "clone", "--depth", "1", "--branch", ref,
                "https://github.com/dawn-used-yeet-alt/hotaudio-extractor.git",
                tempDir.absolutePath,
            )
            run(
                tempDir,
                "cargo", "build",
                "--manifest-path", "bindings/Cargo.toml",
                "--locked", "--release", "-p", "hotaudio-ffi",
            )

            val sourceLibrary = File(tempDir, "bindings/target/release/$libraryName")
            if (!sourceLibrary.exists()) {
                throw GradleException("hotaudio FFI library was not built at ${sourceLibrary.absolutePath}")
            }

            val generatedDir = File(tempDir, "bindings/out")
            run(
                File(tempDir, "bindings"),
                "cargo", "run",
                "--manifest-path", "Cargo.toml",
                "--locked", "-p", "uniffi-bindgen", "--",
                "generate", "-l", "kotlin",
                "--out-dir", generatedDir.absolutePath,
                sourceLibrary.absolutePath,
            )

            val generatedBindings = File(generatedDir, "net/hotaudio/extractor/hotaudio_ffi.kt")
            if (!generatedBindings.exists()) {
                throw GradleException("hotaudio Kotlin bindings were not generated")
            }

            outBindings.mkdirs()
            generatedBindings.copyTo(bindingFile, overwrite = true)
            markerFile.writeText(ref)

            outNative.mkdirs()
            sourceLibrary.copyTo(File(outNative, libraryName), overwrite = true)
            println("Built hotaudio FFI for $ref into ${outNative.absolutePath}")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun run(directory: File, vararg command: String) {
        println("Running ${command.joinToString(" ")} in ${directory.absolutePath}")
        val process = ProcessBuilder(*command)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().forEachLine { println(it) }
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("`${command.joinToString(" ")}` exited with code $exit")
        }
    }
}

val buildDesktopHotaudio by tasks.registering(BuildDesktopHotaudioTask::class) {
    group = "desktop"
    description = "Builds the hotaudio UniFFI bindings and native library for this host."
    extractorRef.set(hotaudioExtractorRef)
    bindingsDir.set(hotaudioBindingsDir)
    nativeDir.set(hotaudioNativeDir)
    nativeLibraryName.set(hotaudioNativeLibrary)
}

tasks.matching { it.name == "compileKotlin" }.configureEach {
    dependsOn(buildDesktopHotaudio)
}

tasks.named<Test>("test") {
    dependsOn(buildDesktopHotaudio)
}

tasks.matching {
    it.name == "createDistributable" ||
        it.name == "packageDeb" ||
        it.name == "packageRpm" ||
        it.name == "packageAppImage" ||
        it.name == "packageMsi" ||
        it.name == "packageDmg"
}.configureEach {
    dependsOn(buildDesktopHotaudio)
}
