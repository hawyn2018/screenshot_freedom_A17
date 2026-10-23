plugins {
    id("com.android.application") version "9.4.1" apply false
    // Select KGP 2.4.20 for AGP's built-in Kotlin integration without applying it twice.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
