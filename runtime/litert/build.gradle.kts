plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.runtime.litert"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":runtime:api"))
    implementation(project(":core:model"))
    implementation(libs.litert.android)
    testImplementation(libs.junit)
}
