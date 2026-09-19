import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing, kept out of the repo. Create `keystore.properties` next to this file's root with:
 *
 *     storeFile=instabalance-release.jks
 *     storePassword=...
 *     keyAlias=instabalance
 *     keyPassword=...
 *
 * Both that file and *.jks are gitignored. Without them the release build is simply unsigned, so a
 * fresh clone and CI still build. Losing the .jks means this app can never be updated again, so
 * keep a copy somewhere that is not just this machine.
 */
private val keystorePropsFile = rootProject.file("keystore.properties")
private val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // Notepad and PowerShell's utf8 encoder both prepend a byte-order mark, which Properties
        // reads as part of the first key's name. The build then fails with "path may not be null",
        // which points nowhere near the cause.
        keystorePropsFile.readText().removePrefix("﻿").reader().use { load(it) }
    }
}

android {
    namespace = "com.instabalance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.instabalance"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // So the sample-data controls can be compiled out of a release build entirely.
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 drags in fragment 1.2.5, whose FragmentActivity still enforces the legacy
    // "lower 16 bits only" rule on request codes. ActivityResultRegistry in activity 1.9.x
    // deliberately allocates above that range, so on a FragmentActivity every launcher (the
    // notification permission, the backup file picker) threw IllegalArgumentException the moment
    // it was tapped. Fragment 1.3.0 dropped the check; this pins a current one.
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
