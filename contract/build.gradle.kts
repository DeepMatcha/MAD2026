plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.deepmatcha.mad.contract"
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
    api(libs.androidx.activity)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
