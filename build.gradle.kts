plugins {
    kotlin("multiplatform").apply(false)
    kotlin("plugin.serialization").apply(false)
    kotlin("plugin.compose").apply(false)
    id("com.android.application").apply(false)
    id("com.android.library").apply(false)
    id("org.jetbrains.compose").apply(false)
    id("app.cash.sqldelight").apply(false)
    id("org.jlleitschuh.gradle.ktlint").version("12.2.0").apply(false)
    id("com.vanniktech.maven.publish").apply(false)
}
