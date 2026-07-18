plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.core.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
