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
            freeCompilerArgs += "-Xoverride-konan-properties=cacheableTargets="
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
            }
        }
    }
}

composeNativeApplication {
    applicationName.set("Dawngasm")
    packageName.set("dev.dawngasm")
    executableName.set("Dawngasm")
    packageVersion.set("2.0.0")
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

tasks.matching { it.name.startsWith("package") }.configureEach {
    doFirst {
        val helper = file("../native/clearance-helper/target/release/$clearanceHelperBinary")
        if (helper.exists()) {
            fileTree(layout.buildDirectory) {
                include("**/usr/bin")
                include("**/bin")
                include("**/MacOS")
            }.files.filter { it.isDirectory }.forEach { dir ->
                val dest = dir.resolve(clearanceHelperBinary)
                helper.copyTo(dest, overwrite = true)
                dest.setExecutable(true, false)
            }
        }
    }
}
