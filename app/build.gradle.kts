plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dom.slimlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dom.slimlauncher"
        minSdk = 26
        targetSdk = 34
        // Increases with every GitHub build so each one installs as an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.$versionCode"
    }

    // A fixed key committed to the repo (personal use only), so every build is signed the same
    // way and updates install over the previous version.
    signingConfigs {
        create("personal") {
            storeFile = file("slim.keystore")
            storePassword = "android"
            keyAlias = "slim"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
