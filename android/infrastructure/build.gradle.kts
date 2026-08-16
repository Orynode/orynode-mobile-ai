plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.orynode.mobile.infrastructure"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.litert)
    implementation(libs.litertlm.android)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
