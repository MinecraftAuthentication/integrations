// Stonecutter controller for the Minecraft mod (all loaders, all Minecraft versions).
//
//   ./gradlew ":minecraft:mod:Set active project to 1.20.1-forge"   rewrites src/ for that target (IDE support)
//   ./gradlew :minecraft:mod:buildAll                                builds every target into minecraft/mod/build/libs
//
// Before committing, switch back to the VCS version (see settings.gradle.kts) with
//   ./gradlew ":minecraft:mod:Reset active project"
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.1-neoforge"

stonecutter parameters {
    // `//? if fabric {`, `//? if forge {`, `//? if neoforge {` in sources
    val loader = current.project.substringAfterLast('-')
    constants.match(loader, "fabric", "forge", "neoforge")
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds the mod for every Minecraft version and loader"
    dependsOn(stonecutter.versions.map { ":minecraft:mod:${it.project}:build" })
}

// Every target's collectJar copies its jar into build/libs here, which no target's own clean covers, so old jars
// (e.g. -SNAPSHOT ones) would pile up and get attached to releases. Only libs: stonecutter-cache must survive.
tasks.register<Delete>("clean") {
    group = "build"
    description = "Deletes the collected mod jars in minecraft/mod/build/libs"
    delete(layout.buildDirectory.dir("libs"))
}

tasks.register("runtimeTestAll") {
    group = "verification"
    description = "Runs every target's jar on a real dedicated server (see ModRuntimeTest)"
    dependsOn(stonecutter.versions.map { ":minecraft:mod:${it.project}:runtimeTest" })
}
