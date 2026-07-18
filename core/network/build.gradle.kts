plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.core.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
}
