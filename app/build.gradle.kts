import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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
    implementation(libs.jna)
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

val generateHotaudioBindings by tasks.registering {
    val outputDir = layout.projectDirectory.dir("src/main/java/net/hotaudio/extractor")
    val outputFile = outputDir.file("hotaudio_ffi.kt").asFile
    outputs.file(outputFile)

    doLast {
        if (!outputFile.exists()) {
            val hasCargo = try {
                project.exec { commandLine("cargo", "--version") }.exitValue == 0
            } catch (e: Exception) {
                false
            }
            if (hasCargo) {
                val tempDir = Files.createTempDirectory("hotaudio-extractor").toFile()
                try {
                    println("Cloning hotaudio-extractor to generate UniFFI bindings...")
                    project.exec {
                        commandLine("git", "clone", "--depth", "1", "https://github.com/dawn-used-yeet-alt/hotaudio-extractor.git", tempDir.absolutePath)
                    }
                    println("Building bindings with scripts/build-bindings.sh...")
                    project.exec {
                        workingDir = tempDir
                        commandLine("bash", "scripts/build-bindings.sh", "--release")
                    }
                    val generated = File(tempDir.absolutePath, "bindings/out/net/hotaudio/extractor/hotaudio_ffi.kt")
                    if (generated.exists()) {
                        outputDir.asFile.mkdirs()
                        generated.copyTo(outputFile, overwrite = true)
                        println("Successfully generated ${outputFile.absolutePath}")
                    } else {
                        throw GradleException("Failed to locate generated hotaudio_ffi.kt at ${generated.absolutePath}")
                    }
                    val tlsFile = File(tempDir.absolutePath, "bindings/android/HotaudioTls.kt")
                    val targetTls = outputDir.file("HotaudioTls.kt").asFile
                    if (tlsFile.exists() && !targetTls.exists()) {
                        tlsFile.copyTo(targetTls, overwrite = true)
                    }
                } finally {
                    tempDir.deleteRecursively()
                }
            } else {
                println("Cargo not found; skipping hotaudio bindings generation")
            }
        }
    }
}

val buildHotaudioNative by tasks.registering {
    dependsOn(generateHotaudioBindings)
    val jniDir = layout.projectDirectory.dir("src/main/jniLibs")
    val arm64So = jniDir.file("arm64-v8a/libhotaudio_ffi.so").asFile
    val x86_64So = jniDir.file("x86_64/libhotaudio_ffi.so").asFile
    outputs.files(arm64So, x86_64So)

    doLast {
        if (!arm64So.exists() || !x86_64So.exists()) {
            val hasCargo = try {
                project.exec { commandLine("cargo", "--version") }.exitValue == 0
            } catch (e: Exception) {
                false
            }
            if (hasCargo) {
                val tempDir = Files.createTempDirectory("hotaudio-native").toFile()
                try {
                    project.exec {
                        commandLine("git", "clone", "--depth", "1", "https://github.com/dawn-used-yeet-alt/hotaudio-extractor.git", tempDir.absolutePath)
                    }
                    val hasCargoNdk = try {
                        project.exec { commandLine("cargo", "ndk", "--version") }.exitValue == 0
                    } catch (e: Exception) {
                        false
                    }
                    if (!hasCargoNdk) {
                        println("Installing cargo-ndk...")
                        project.exec { commandLine("cargo", "install", "cargo-ndk", "--locked") }
                    }
                    project.exec { commandLine("rustup", "target", "add", "aarch64-linux-android", "x86_64-linux-android") }
                    println("Building libhotaudio_ffi.so for arm64-v8a and x86_64...")
                    project.exec {
                        workingDir = File(tempDir.absolutePath, "bindings")
                        commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-o", jniDir.asFile.absolutePath, "build", "--manifest-path", "Cargo.toml", "--release", "-p", "hotaudio-ffi")
                    }
                    println("Successfully built native libraries in ${jniDir.asFile.absolutePath}")
                } catch (e: Exception) {
                    println("Warning: Could not build native libraries: ${e.message}")
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(generateHotaudioBindings)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildHotaudioNative)
}
