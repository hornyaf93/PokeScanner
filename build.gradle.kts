plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourname.pokescanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yourname.pokescanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // This forces the compiler to look directly at the flat root directory for all code files
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("."))
            manifest.srcFile("AndroidManifest.xml")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}
