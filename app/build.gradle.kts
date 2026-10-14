plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.flexiatom.floatingassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flexiatom.floatingassistant"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.core)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime)
}
