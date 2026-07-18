plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.optimizer"
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
    implementation(project(":runtime:api"))
    testImplementation(libs.junit)
}
