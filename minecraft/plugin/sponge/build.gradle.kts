import modrinth.minecraftReleases

plugins {
    id("mcauth.shaded-plugin")
}

dependencies {
    implementation(project(":minecraft:common"))
    // API 8 (Minecraft 1.16.5) is the oldest API with this plugin model; the jar also loads on later Sponge APIs
    compileOnly(libs.sponge.api)

    testImplementation(testFixtures(project(":minecraft:common")))
    testImplementation(libs.sponge.api)
    testImplementation(libs.mockito.core)
}

modrinth {
    versionName.set("MinecraftAuth $version for Sponge")
    loaders.add("sponge")
    // API 8 (1.16.5) introduced this plugin model; API 7 (1.12.2) can't load the jar
    gameVersions.set(minecraftReleases(">=1.16.5"))
}
