plugins {
    alias(libs.plugins.android.library) apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
        maven { url = uri("https://jitpack.io") }
        flatDir { dirs(rootProject.file(".local/dependencies")) }
    }
}
