plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.cluster.core"
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
    implementation(project(":core:rag"))
    testImplementation(libs.junit)
}
