pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.kikugie.dev/releases") { name = "kikugie" }
        maven("https://maven.architectury.dev/") { name = "architectury" }
        maven("https://maven.fabricmc.net/") { name = "fabric" }
        maven("https://maven.minecraftforge.net/") { name = "forge" }
        maven("https://maven.neoforged.net/releases/") { name = "neoforge" }
    }
    plugins {
        id("dev.architectury.loom") version "1.17.493"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.10"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigot" }
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "sponge" }
        maven("https://oss.sonatype.org/content/repositories/snapshots/") { name = "sonatype-snapshots" }
    }
}

rootProject.name = "integrations"

// API clients
include(":lib:jvm")
include(":lib:go")
include(":lib:js")

// Caddy module (Go)
include(":caddy")

// Minecraft
include(":minecraft:common")
include(":minecraft:plugin:bukkit")
include(":minecraft:plugin:sponge")
include(":minecraft:proxy:velocity")
include(":minecraft:proxy:bungee")

// Mods: one Stonecutter source tree in minecraft/mod, one Gradle project per "<minecraft>-<loader>" target.
// minecraft/mod/versions/<target>/gradle.properties holds each target's dependency versions.
// Loom sets up Minecraft for every target while configuring it, which takes minutes, so builds that don't need the
// mods (the Go and JavaScript CI jobs) leave them out with -Pmcauth.mods=false.
if (providers.gradleProperty("mcauth.mods").orNull != "false") {
    include(":minecraft:mod")
    stonecutter {
        create(":minecraft:mod") {
            versions(
                "1.16.5-fabric" to "1.16.5",
                "1.18.2-fabric" to "1.18.2",
                "1.19.2-fabric" to "1.19.2",
                "1.20.1-fabric" to "1.20.1",
                "1.21.1-fabric" to "1.21.1",
                "1.21.11-fabric" to "1.21.11",
                "26.3-fabric" to "26.3",
                "1.16.5-forge" to "1.16.5",
                "1.18.2-forge" to "1.18.2",
                "1.19.2-forge" to "1.19.2",
                "1.20.1-forge" to "1.20.1",
                "1.21.1-neoforge" to "1.21.1",
                "1.21.11-neoforge" to "1.21.11",
                "26.3-neoforge" to "26.3",
            )
            vcsVersion = "1.21.1-neoforge"
        }
    }
}
