plugins {
    id("com.android.application")
}

android {
    namespace = "com.audeyshelf.audiobook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.audeyshelf.audiobook"
        minSdk = 16
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.media:media:1.3.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.googlecode.mp4parser:isoparser:1.1.22")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
