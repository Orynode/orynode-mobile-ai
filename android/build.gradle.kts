plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// LiteRT-LM AARs call SendChannel.close$default (coroutines 1.11+ bytecode) while their
// POM still declares 1.9.0 — force a compatible coroutines line app-wide.
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
                "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
            )
        }
    }
}

