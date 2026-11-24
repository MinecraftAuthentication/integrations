plugins {
    id("mcauth.kotlin-jvm")
    // FakeMembership and friends, shared with the platform adapters' tests
    `java-test-fixtures`
}

dependencies {
    api(project(":lib:jvm"))
    api(libs.boosted.yaml)
    implementation(libs.evalex)
    implementation(libs.caffeine)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("minecraftauth-version.properties") { expand(props) }
}
