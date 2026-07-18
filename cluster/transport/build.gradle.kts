plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.cluster.transport"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":cluster:core"))
    implementation(project(":core:api"))
    implementation(project(":core:security"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    testImplementation(libs.junit)
}
