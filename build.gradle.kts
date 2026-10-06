plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "io.github.numq.kempo"
    val ref = System.getenv("GITHUB_REF")
    version = if (ref != null && ref.startsWith("refs/tags/")) {
        ref.removePrefix("refs/tags/").removePrefix("v")
    } else {
        providers.gradleProperty("version").getOrElse("0.0.0")
    }
}