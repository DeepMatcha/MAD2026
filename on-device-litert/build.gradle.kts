plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.deepmatcha.mad.ondevice.litert"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":backend"))
    implementation(libs.litert.lm.android)
    testImplementation(libs.junit)
}
