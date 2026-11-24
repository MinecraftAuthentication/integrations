import modrinth.minecraftReleases

plugins {
    id("mcauth.shaded-plugin")
}

dependencies {
    implementation(project(":minecraft:common"))
    // compiled against 1.16.5 so the jar also loads on old servers; works through current Paper and Folia
    compileOnly(libs.spigot.api)

    testImplementation(testFixtures(project(":minecraft:common")))
    testImplementation(libs.spigot.api)
    testImplementation(libs.mockito.core)
}

modrinth {
    versionName.set("MinecraftAuth $version for Bukkit")
    loaders.addAll("bukkit", "spigot", "paper", "purpur", "folia")
    // only uses API that predates 1.8 (AsyncPlayerPreLoginEvent + UUIDs, getOperators) on Java 8 bytecode;
    // pre-1.13 servers ignore plugin.yml's api-version
    gameVersions.set(minecraftReleases(">=1.8"))
}
