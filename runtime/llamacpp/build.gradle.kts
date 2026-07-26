plugins {
    alias(libs.plugins.android.library)
}

val llamaVendor = layout.projectDirectory.dir("vendor").asFile
val llamaNativeReady = llamaVendor.resolve("jni/arm64-v8a/libllama.so").isFile &&
    llamaVendor.resolve("include/llama.h").isFile

android {
    namespace = "dev.androml.runtime.llamacpp"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("proguard-rules.pro")
        buildConfigField("boolean", "LLAMA_CPP_BUNDLED", llamaNativeReady.toString())
        if (llamaNativeReady) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                }
            }
        }
    }

    buildFeatures { buildConfig = true }

    if (llamaNativeReady) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
        sourceSets {
            getByName("main") {
                jniLibs.srcDir(llamaVendor.resolve("jni"))
            }
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":runtime:api"))
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
