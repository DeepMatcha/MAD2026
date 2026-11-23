plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.deepmatcha.mad"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        applicationId = "org.deepmatcha.mad"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":on-device-litert"))
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":contract"))
    implementation(project(":demo-data"))
}
