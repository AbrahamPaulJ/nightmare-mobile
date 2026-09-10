// ⚠ Versions are pinned to what is ALREADY in this machine's Gradle cache
// (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.13) -- the same set DreamUI uses -- so the
// first build needs minimal downloads and a failure is attributable to our code
// rather than to dependency resolution.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // ⭐ The inner loop (docs/UI.md §3). Renders composables to PNG on the JVM
    // with no device and no IDE, which is what makes UI iteration cheap enough
    // to actually do -- render-compose-preview needs a running Android Studio
    // and there is none here.
    id("io.github.takahirom.roborazzi") version "1.26.0" apply false
}
