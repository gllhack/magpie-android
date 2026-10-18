plugins {
    id("com.android.application")
}

android {
    namespace = "com.magpie.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.magpie.android"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The Go gateway is built by scripts/build-magpie-android.ps1 into
        // src/main/jniLibs/<abi>/libmagpie.so; Gradle only packages it, the
        // NDK is used by that script and not by this build.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Signed with the debug key so a release APK installs without a
            // keystore of its own. Replace with your own signingConfig for
            // anything you hand out.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // The gateway is exec'd from the app's native library directory,
            // so the libraries must be extracted onto disk.
            useLegacyPackaging = true
            // Bun is carried the same way (jniLibs/<abi>/libmagpiebun.so) and
            // is exec'd from there too: AGP must not strip it.
            keepDebugSymbols += "**/libmagpiebun.so"
        }
    }

    androidResources {
        // No need to compress the big binary further; keeps extraction quick.
        noCompress += "so"
    }
}
