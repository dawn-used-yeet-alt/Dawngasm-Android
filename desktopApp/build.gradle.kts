import org.gradle.api.file.ConfigurableFileCollection
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

            // Keep the bundled runtime small. The Compose plugin already adds
            // java.base, java.desktop, java.logging, and jdk.crypto.ec.
            // jdk.unsupported is required by JNA (and therefore VLCJ and the
            // hotaudio bindings), jdk.httpserver backs the loopback stream
            // server, and zipfs/charsets keep resource loading and non-UTF-8
            // HTML/file names working. jdk.localedata is intentionally left
            // out: the UI is English and only ISO date formatters are used.
            modules(
                "jdk.charsets",
                "jdk.httpserver",
                "jdk.unsupported",
                "jdk.zipfs",
            )

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

// --- material icon tree-shaking ------------------------------------------
//
// material-icons-extended is ~37 MB of generated classes, but the app uses
// only a few dozen icons. R8/ProGuard can shrink the whole desktop image, but
// it needs a broad keep-rule set and changes every packaged jar. Pruning the
// icon jar in the app image is deterministic, keeps the rest of the runtime
// untouched, and gives the same disk-size win for this dependency.

abstract class PruneMaterialIconsTask : DefaultTask() {
    @get:Internal
    abstract val appImageRoot: DirectoryProperty

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    private val sourceIconPattern = Regex(
        """androidx\.compose\.material\.icons\.((?:automirrored\.)?(?:filled|outlined|rounded|twotone|sharp))\.([A-Za-z0-9_]+)"""
    )
    private val iconEntryPattern = Regex(
        """^androidx/compose/material/icons/((?:automirrored/)?(?:filled|outlined|rounded|twotone|sharp))/([^/]+)\.class$"""
    )

    @TaskAction
    fun prune() {
        val keep = mutableSetOf<String>()
        sources.files.asSequence()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { source ->
                sourceIconPattern.findAll(source.readText()).forEach { match ->
                    val variant = match.groupValues[1].replace('.', '/')
                    keep.add("$variant/${match.groupValues[2]}")
                }
            }
        if (keep.isEmpty()) {
            logger.warn("No material icon references found; leaving the icon jar unchanged.")
            return
        }

        val root = appImageRoot.get().asFile
        val jars = root.walkTopDown()
            .filter {
                it.isFile &&
                    it.extension == "jar" &&
                    it.name.startsWith("material-icons-extended")
            }
            .toList()
        if (jars.isEmpty()) {
            throw GradleException(
                "No material-icons-extended jar found under ${root.absolutePath}; " +
                    "run createDistributable first."
            )
        }

        val report = StringBuilder()
        jars.forEach { jar ->
            val found = mutableSetOf<String>()
            var kept = 0
            var removed = 0
            var copied = 0
            val temporary = File(jar.parentFile, "${jar.name}.pruned")

            ZipFile(jar).use { zip ->
                ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val icon = iconKey(entry.name)
                        if (icon != null) {
                            found.add(icon)
                            if (icon !in keep) {
                                removed++
                                continue
                            }
                            kept++
                        }
                        val copy = ZipEntry(entry.name)
                        if (entry.time >= 0) copy.time = entry.time
                        output.putNextEntry(copy)
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { it.copyTo(output) }
                        }
                        output.closeEntry()
                        copied++
                    }
                }
            }
            Files.move(temporary.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)

            val missing = keep - found
            report.appendLine(
                "${jar.absolutePath}: kept $kept icon classes, removed $removed, copied $copied entries"
            )
            if (missing.isNotEmpty()) {
                report.appendLine(
                    "  not in this jar (defined by material-icons-core): ${missing.sorted().joinToString()}"
                )
            }
        }

        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(report.toString())
        logger.lifecycle(report.toString().trim())
    }

    private fun iconKey(entryName: String): String? {
        val match = iconEntryPattern.matchEntire(entryName) ?: return null
        val className = match.groupValues[2].substringBefore('$')
        val icon = className.removeSuffix("Kt")
        return "${match.groupValues[1]}/$icon"
    }
}

val pruneMaterialIcons by tasks.registering(PruneMaterialIconsTask::class) {
    group = "desktop"
    description = "Removes unused material-icons-extended classes from the app image."
    dependsOn("createDistributable")
    // The task rewrites a file inside another task's output directory, so it
    // must rerun whenever createDistributable runs, even if sources are
    // unchanged.
    outputs.upToDateWhen { false }
    appImageRoot.set(layout.buildDirectory.dir("compose/binaries/main/app"))
    sources.from(
        rootProject.fileTree("composeApp/src") { include("**/*.kt") },
        rootProject.fileTree("shared/src") { include("**/*.kt") },
        rootProject.fileTree("desktopApp/src") { include("**/*.kt") },
    )
    reportFile.set(layout.buildDirectory.file("reports/material-icons-prune.txt"))
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(pruneMaterialIcons)
}

tasks.matching {
    it.name == "packageDeb" ||
        it.name == "packageRpm" ||
        it.name == "packageAppImage" ||
        it.name == "packageMsi" ||
        it.name == "packageDmg"
}.configureEach {
    dependsOn(pruneMaterialIcons)
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
