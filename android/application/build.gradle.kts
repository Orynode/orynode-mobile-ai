plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
