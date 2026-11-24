import modrinth.minecraftReleases

plugins {
    id("mcauth.shaded-plugin")
}

mcauth { javaVersion.set(17) }

dependencies {
    implementation(project(":minecraft:common"))
    compileOnly(libs.velocity.api)

    testImplementation(testFixtures(project(":minecraft:common")))
    testImplementation(libs.velocity.api)
    testImplementation(libs.mockito.core)
}

modrinth {
    versionName.set("MinecraftAuth $version for Velocity")
    loaders.add("velocity")
    // the proxy doesn't care which client version connects
    gameVersions.set(minecraftReleases(">=1.8"))
}
