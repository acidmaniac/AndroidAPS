plugins {
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.com.android.tools.build)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.allopen)
    // On classpath so the kover-module-dependencies precompiled convention plugin can apply
    // id("org.jetbrains.kotlinx.kover").
    implementation(libs.kover.gradlePlugin)
}