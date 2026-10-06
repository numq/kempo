plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.benchmark)
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
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
    }
}

benchmark {
    targets {
        register("jvm")
    }
}