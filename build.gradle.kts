// Versions are from memory (Nov 2026). If Gradle sync complains, let Android Studio's
// "AGP Upgrade Assistant" bump them; the code itself does not depend on exact versions.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
