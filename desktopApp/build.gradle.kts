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
