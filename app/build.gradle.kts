// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lxlauncher"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.lxlauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.0"
        testInstrumentationRunner = "com.lxlauncher.test.SelfTestInstrumentation"
        ndk {
            // arm64 for phones and tablets, x86_64 for Waydroid and emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                // VHDP's own shared library is not used: Tedit runs phdp. xterm is dracxterm's terminal engine.
                targets += listOf("lxnative", "lxdb", "phdp", "vhdp_loader", "xterm")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
            // The C++ core and lxdb are optimised in debug builds too, with their debug info kept. Built as Debug
            // (-O0) they ran about 20 times slower: blurring a 180x400 wallpaper took 170 ms on the Infinix test
            // phone instead of a few, and the phone runs debug builds day to day.
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_BUILD_TYPE=RelWithDebInfo")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config on purpose: release keys are never stored in
            // this repository. Sign the unsigned APK with apksigner.
        }
    }

    buildFeatures {
        buildConfig = true
        // IEmbedService: the binder between LX and its helper process.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    // dracxterm's terminal, kept in its own folder (GPL-3.0, see third_party/dracxterm/README.lx.md): its Kotlin
    // classes, its shell files, and BusyBox, the shell Tedit's terminal starts when Debian is not set up.
    sourceSets["main"].java.srcDirs("../third_party/dracxterm/kotlin")
    sourceSets["main"].assets.srcDirs("../third_party/dracxterm/assets")
    sourceSets["main"].jniLibs.srcDirs("../third_party/dracxterm/jniLibs")
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")

    packaging {
        jniLibs.useLegacyPackaging = false
    }
    androidResources {
        // The Debian image for Tedit is xz already; storing it uncompressed lets it stream straight from the APK.
        noCompress += "xz"
    }
    bundle {
        // Tedit's language setting switches between English, Indonesian and Chinese whatever the phone's language
        // is, so an app bundle has to keep every language in the base APK.
        language {
            enableSplit = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += listOf("SetTextI18n")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

