plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.core.files"
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

