import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.hawyn.screenshotfreedoma17"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.hawyn.screenshotfreedoma17"
        minSdk = 26
        targetSdk = 37
        versionCode = 43
        versionName = "3.5.9"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    packaging {
        resources {
            // Keep META-INF/xposed/*: the modern module entry point and scope.
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
}
