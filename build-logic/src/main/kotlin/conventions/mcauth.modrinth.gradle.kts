// Publishes the project's jar as a version of the MinecraftAuth project on Modrinth (Minotaur): `./gradlew modrinth`.
// The applying build sets what differs per artifact -- uploadFile, loaders, gameVersions (see minecraftReleases()),
// and versionName/versionNumber if the defaults don't fit.
//
// Properties / environment:
//   MODRINTH_TOKEN (env) or -Pmodrinth.token        personal access token with the "Create versions" scope
//   modrinth_project (gradle.properties)             Modrinth project slug or ID
//   MODRINTH_CHANGELOG (env) or -Pmodrinth.changelog Markdown changelog for the version
//   -Pmodrinth.debug=true                            build the request and log it, without uploading
//
// The version type follows the version: *-alpha* -> alpha, *-beta* / *-rc* -> beta, otherwise release.
// -SNAPSHOT versions are refused, and so is a real upload without a changelog (generate one with
// scripts/changelog.sh minecraft, see RELEASING.md).

plugins {
    id("com.modrinth.minotaur")
}

val publishedVersion = provider { project.version.toString() }


modrinth {
    // empty rather than absent, so a debug run works without one; the check below catches a real upload without it
    token.set(providers.gradleProperty("modrinth.token").orElse(providers.environmentVariable("MODRINTH_TOKEN")).orElse(""))
    projectId.set(providers.gradleProperty("modrinth_project").orElse(""))
    versionNumber.set(publishedVersion)
    versionType.set(publishedVersion.map {
        val v = it.lowercase()
        when {
            "alpha" in v -> "alpha"
            "beta" in v || "-rc" in v || ".rc" in v -> "beta"
            else -> "release"
        }
    })
    changelog.set(
        providers.gradleProperty("modrinth.changelog")
            .orElse(providers.environmentVariable("MODRINTH_CHANGELOG"))
            .filter(String::isNotBlank)
            .orElse("")
    )
    // Minotaur only knows plain Loom/ForgeGradle; every build here declares its loaders explicitly
    detectLoaders.set(false)
    // every artifact here runs only on a dedicated server or proxy, never in singleplayer; Modrinth's default
    // (server_only) would also tag it singleplayer
    environment.set("DEDICATED_SERVER_ONLY")
    debugMode.set(providers.gradleProperty("modrinth.debug").map(String::toBoolean).orElse(false))
}

tasks.named("modrinth") {
    val version = publishedVersion
    val debug = modrinth.debugMode
    val token = modrinth.token
    val modrinthProject = modrinth.projectId
    val changelogText = modrinth.changelog
    doFirst {
        if (modrinthProject.get().isBlank()) {
            throw GradleException("No Modrinth project: set modrinth_project in gradle.properties (or -Pmodrinth_project)")
        }
        if (debug.get()) return@doFirst
        if (token.get().isBlank()) {
            throw GradleException("No Modrinth token: set the MODRINTH_TOKEN environment variable or -Pmodrinth.token")
        }
        if (changelogText.get().isBlank()) {
            throw GradleException(
                "No changelog: set MODRINTH_CHANGELOG (or -Pmodrinth.changelog), e.g. " +
                    "MODRINTH_CHANGELOG=\"$(bash scripts/changelog.sh minecraft)\""
            )
        }
        if (version.get().contains("-SNAPSHOT")) {
            throw GradleException(
                "Refusing to publish snapshot ${version.get()} to Modrinth; " +
                    "set a release version (./gradlew setVersion -PnewVersion=X.Y.Z)"
            )
        }
    }
}
