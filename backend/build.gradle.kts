plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.deepmatcha.mad.backend"
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
    api(project(":contract"))
    testImplementation(project(":demo-data"))
    testImplementation(libs.junit)
}
