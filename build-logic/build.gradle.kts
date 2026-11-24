plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.shadow.gradle.plugin)
    implementation(libs.minotaur.gradle.plugin)
}
