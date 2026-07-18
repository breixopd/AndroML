plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.core.security"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:api"))
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
