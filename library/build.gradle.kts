plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "io.github.numq.kempo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(
        groupId = "io.github.numq.kempo", artifactId = "kempo", version = project.version.toString()
    )

    pom {
        name.set("Kempo")
        description.set("High-performance, pure Kotlin Multiplatform library for real-time audio time-stretching and pitch-shifting.")
        url.set("https://github.com/numq/kempo")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("numq")
                name.set("numq")
                url.set("https://github.com/numq")
            }
        }

        scm {
            url.set("https://github.com/numq/kempo")
            connection.set("scm:git:git://github.com/numq/kempo.git")
            developerConnection.set("scm:git:ssh://git@github.com/numq/kempo.git")
        }
    }
}