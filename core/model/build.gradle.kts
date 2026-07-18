plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.core.model"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}

