plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.runtime.api"
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
    testImplementation(libs.junit)
}
