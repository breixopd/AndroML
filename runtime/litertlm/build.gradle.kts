plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.runtime.litertlm"
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
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
