plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.core.workflow"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:tools"))
    testImplementation(libs.junit)
}
