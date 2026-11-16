import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
}

dependencies {
    implementation(project(":composeApp"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.swing)
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
