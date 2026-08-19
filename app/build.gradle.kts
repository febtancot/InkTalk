plugins {
    id("com.android.application")
}

android {
    namespace = "com.inktalk.ime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.inktalk.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    testImplementation("junit:junit:4.13.2")
}
