import modrinth.minecraftReleases

plugins {
    id("mcauth.shaded-plugin")
}

dependencies {
    implementation(project(":minecraft:common"))
    compileOnly(libs.bungeecord.api)

    testImplementation(testFixtures(project(":minecraft:common")))
    testImplementation(libs.bungeecord.api)
    testImplementation(libs.mockito.core)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("MinecraftAuth-BungeeCord")
}

modrinth {
    versionName.set("MinecraftAuth $version for BungeeCord")
    loaders.addAll("bungeecord", "waterfall")
    // the proxy doesn't care which client version connects
    gameVersions.set(minecraftReleases(">=1.8"))
}
