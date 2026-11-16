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
            entryPoint = "dev.dawngasm.spike.main"
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.configureEach {
            freeCompilerArgs += "-Xoverride-konan-properties=cacheableTargets="
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
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
            }
        }
    }
}

composeNativeApplication {
    applicationName.set("Dawngasm Native Spike")
    packageName.set("dev.dawngasm.spike")
    executableName.set("dawngasm-native-spike")
    description.set("Standalone native desktop shell for Dawngasm")
    categories.set(listOf("AudioVideo", "Audio", "Player"))
    startupWmClass.set("Dawngasm")
}
