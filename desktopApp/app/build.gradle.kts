plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.13.0-beta01"
    id("dev.brahmkshatriya.compose") version "1.13.0-beta01"
}

val composeNativeVersion = "1.13.0-beta01"
val ktorVersion = "3.1.3"

kotlin {
    jvmToolchain(21)

    desktopNative {
        binaries.executable {
            entryPoint = "dev.dawngasm.desktop.main"
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.configureEach {
            freeCompilerArgs += listOf(
                "-Xoverride-konan-properties=cacheableTargets=",
                "-Xbinary=gc=cms",
            )
        }
        val engineDep = when (name) {
            "linuxX64" -> "io.ktor:ktor-client-curl:$ktorVersion"
            "mingwX64" -> "io.ktor:ktor-client-winhttp:$ktorVersion"
            "macosX64", "macosArm64" -> "io.ktor:ktor-client-darwin:$ktorVersion"
            else -> "io.ktor:ktor-client-curl:$ktorVersion"
        }
        compilations.getByName("main").defaultSourceSet.dependencies {
            implementation(engineDep)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir("src/commonMain/kotlin")
            kotlin.srcDir("../../shared/src/commonMain/kotlin")
            kotlin.srcDir("../../composeApp/src/commonMain/kotlin")

            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
                implementation("com.russhwolf:multiplatform-settings:1.3.0")
                implementation("com.squareup.okio:okio:3.10.2")
                implementation("dev.brahmkshatriya.compose.material3:material3:$composeNativeVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }

        desktopNativeMain {
            kotlin.srcDir("../../composeApp/src/desktopMain/kotlin")

            dependencies {
                implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
                implementation("dev.brahmkshatriya.kmplayer:kmplayer:0.2.1")
                implementation("dev.brahmkshatriya.kmedianotification:lib:0.1.1")
                implementation("io.ktor:ktor-network:$ktorVersion")
            }
        }
    }
}

composeNativeApplication {
    applicationName.set("Dawngasm")
    packageName.set("dev.dawngasm")
    executableName.set("Dawngasm")
    packageVersion.set("2.1.1")
    description.set("Audio browser and player for soundgasm, whyp, and audiochan")
    vendor.set("Dawngasm")
    categories.set(listOf("AudioVideo", "Audio", "Player"))
    startupWmClass.set("Dawngasm")

    val hostOs = System.getProperty("os.name")
    val icon = when {
        hostOs.startsWith("Mac", ignoreCase = true) -> file("../icons/dawngasm.icns")
        hostOs.startsWith("Windows", ignoreCase = true) -> file("../icons/dawngasm.ico")
        else -> file("../icons/dawngasm.png")
    }
    if (icon.exists()) {
        iconFile.set(icon)
    }
}

val clearanceHelperBinary = if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
    "dawngasm-clearance-helper.exe"
} else {
    "dawngasm-clearance-helper"
}

fun stageClearanceHelper() {
    val helper = file("../native/clearance-helper/target/release/$clearanceHelperBinary")
    if (!helper.exists()) return
    val buildDir = layout.buildDirectory.get().asFile
    if (!buildDir.exists()) return

    buildDir.walkTopDown().filter { it.isDirectory }.forEach { dir ->
        if (dir.name.endsWith(".AppDir")) {
            val usrBin = dir.resolve("usr/bin").apply { mkdirs() }
            val dest = usrBin.resolve(clearanceHelperBinary)
            helper.copyTo(dest, overwrite = true)
            dest.setExecutable(true, false)
            println("Staged clearance helper to $dest")
        } else if (dir.name == "MacOS" && dir.parentFile?.name == "Contents") {
            val dest = dir.resolve(clearanceHelperBinary)
            helper.copyTo(dest, overwrite = true)
            dest.setExecutable(true, false)
            println("Staged clearance helper to $dest")
        } else if (dir.name == "bin" && (dir.parentFile?.name?.contains("main") == true || dir.parentFile?.name?.contains("release") == true)) {
            val dest = dir.resolve(clearanceHelperBinary)
            helper.copyTo(dest, overwrite = true)
            dest.setExecutable(true, false)
            println("Staged clearance helper to $dest")
        }
    }
}

tasks.matching { it.name.startsWith("prepare") }.configureEach {
    doLast { stageClearanceHelper() }
}
tasks.matching { it.name.startsWith("package") }.configureEach {
    doFirst { stageClearanceHelper() }
}
