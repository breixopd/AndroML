plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val appVersion = rootProject.file("VERSION").readText().trim()
val appVersionParts = appVersion.split('.')
require(appVersionParts.size == 3 && appVersionParts.all { it.toIntOrNull() != null }) {
    "VERSION must contain a numeric MAJOR.MINOR.PATCH value"
}
val appVersionCode = appVersionParts[0].toLong() * 1_000_000L +
    appVersionParts[1].toLong() * 1_000L +
    appVersionParts[2].toLong()
require(appVersionCode in 1..2_100_000_000L) {
    "VERSION produces an invalid Android versionCode: $appVersionCode"
}

android {
    namespace = "dev.androml.app"
    compileSdk = 37

    val releaseKeystorePath = providers.environmentVariable("ANDROML_RELEASE_KEYSTORE").orNull
    val releaseStorePassword = providers.environmentVariable("ANDROML_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("ANDROML_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("ANDROML_RELEASE_KEY_PASSWORD").orNull

    defaultConfig {
        applicationId = "dev.androml.app"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode.toInt()
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"google-play\"")
        }
        create("oss") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"github-and-foss\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (
                releaseKeystorePath != null &&
                releaseStorePassword != null &&
                releaseKeyAlias != null &&
                releaseKeyPassword != null
            ) {
                signingConfig = signingConfigs.create("testRelease") {
                    storeFile = file(releaseKeystorePath)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
            excludes += "/META-INF/services/reactor.blockhound.integration.BlockHoundIntegration"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:files"))
    implementation(project(":core:database"))
    implementation(project(":core:rag"))
    implementation(project(":core:api"))
    implementation(project(":core:agents"))
    implementation(project(":core:security"))
    implementation(project(":core:device"))
    implementation(project(":core:network"))
    implementation(project(":api:server"))
    implementation(project(":cluster:transport"))
    implementation(project(":runtime:api"))
    implementation(project(":runtime:litertlm"))
    implementation(project(":runtime:service"))
    implementation(project(":optimizer"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
