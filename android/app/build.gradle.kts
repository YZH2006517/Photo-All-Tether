plugins {
    id("com.android.application")
}

// The phone app's repository (git submodule third_party/Photo-All-Android): the film engine's
// sources and the film / LUT assets come from there. -Pphotoall.phone=<path> uses another checkout.
val phone: String = (findProperty("photoall.phone") as String?)
    ?: rootProject.file("../third_party/Photo-All-Android").invariantSeparatorsPath

android {
    namespace = "org.photoall.tether"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.photoall.tether"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DPHOTOALL_PHONE=$phone" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main") {
            // filmsim/ (spektrafilm data) and luts/ (creative LUTs) of the phone app
            assets.srcDir("$phone/android/app/src/main/assets")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "lut"
        noCompress += "rgb"
        // the phone app's film-edge fonts are not used here (its 3D cartridge is)
        ignoreAssetsPattern = "!fonts:!.*:!*~"
    }
}
