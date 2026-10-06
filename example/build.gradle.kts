import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)

    jvm()

    compilerOptions {
        freeCompilerArgs.add("-opt-in=io.github.numq.kempo.InternalKempoApi")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.library)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.numq.kempo.example.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "io.github.numq.kempo.example"
            packageVersion = "1.0.0"
        }
    }
}