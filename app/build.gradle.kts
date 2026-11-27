
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Existing team modules
    implementation(project(":contract"))
    implementation(project(":demo-data"))
    implementation("androidx.compose.material:material-icons-extended")
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))

    // Jetpack Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Compose Preview
    debugImplementation(libs.androidx.compose.ui.tooling)
}
