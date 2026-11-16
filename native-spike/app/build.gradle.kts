plugins {
    kotlin("multiplatform") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.13.0-beta01"
    id("dev.brahmkshatriya.compose") version "1.13.0-beta01"
}

val composeNativeVersion = "1.13.0-beta01"

kotlin {
    jvmToolchain(21)

    desktopNative {
        binaries.executable {
            entryPoint = "dev.dawngasm.spike.main"
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.configureEach {
            freeCompilerArgs += "-Xoverride-konan-properties=cacheableTargets="
        }
    }

    sourceSets {
        desktopNativeMain.dependencies {
            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.material3:material3:$composeNativeVersion")
            implementation("dev.brahmkshatriya.kmplayer:kmplayer:0.2.1")
            implementation("dev.brahmkshatriya.kmedianotification:lib:0.1.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}

composeNativeApplication {
    applicationName.set("Dawngasm Native Spike")
    packageName.set("dev.dawngasm.spike")
    executableName.set("dawngasm-native-spike")
    description.set("Feasibility spike for the Dawngasm native desktop shell")
    categories.set(listOf("AudioVideo", "Audio", "Player"))
    startupWmClass.set("Dawngasm")
}
