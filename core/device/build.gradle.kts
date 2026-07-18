plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.core.device"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":core:model"))
    androidTestImplementation(libs.androidx.test.ext.junit)
}
