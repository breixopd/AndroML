plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androml.runtime.service"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":runtime:api"))
    implementation(project(":runtime:litertlm"))
    implementation(project(":runtime:onnx"))
    implementation(project(":runtime:litert"))
    implementation(project(":runtime:executorch"))
    implementation(project(":runtime:llamacpp"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
