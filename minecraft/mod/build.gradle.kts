// Shared build script for every Stonecutter target in versions/<minecraft>-<loader>.
// The target's gradle.properties sets `loom.platform` (fabric / forge / neoforge) and the dependency versions.
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import modrinth.minecraftReleases
import runtimetest.ModRuntimeTest
import runtimetest.ServerSlots

plugins {
    id("dev.architectury.loom") apply false
    id("mcauth.kotlin-jvm")
    id("mcauth.shadow")
    id("mcauth.modrinth")
}

val minecraftVersion = property("minecraft_version") as String
val loader = property("loom.platform") as String
val target = stonecutter.current.project
val targetJava = (property("java_version") as String).toInt()
val minecraftRange = (findProperty("minecraft_range") ?: "*").toString()
val loaderRange = (findProperty("loader_range") ?: "*").toString()
// Minecraft 26.1+ ships unobfuscated: no mappings, nothing to remap
val obfuscated = minecraftVersion.startsWith("1.")

if (!obfuscated) apply(plugin = "dev.architectury.loom-no-remap")
apply(plugin = "dev.architectury.loom")
val loom = extensions.getByType<LoomGradleExtensionAPI>()

val baseVersion = version.toString()
version = "$baseVersion+$target"
base.archivesName.set("MinecraftAuth-" + loader.replaceFirstChar(Char::uppercaseChar) + "-" + minecraftVersion)
tasks.withType<AbstractArchiveTask>().configureEach { archiveVersion.set(baseVersion) }
mcauth { javaVersion.set(targetJava) }

repositories {
    maven("https://maven.fabricmc.net/") { name = "fabric" }
    maven("https://maven.minecraftforge.net/") { name = "forge" }
    maven("https://maven.neoforged.net/releases/") { name = "neoforge" }
}

loom.apply {
    silentMojangMappingsLicense()
    // Mixins are written in Kotlin, which the mixin annotation processor can't see; Loom remaps them from bytecode instead
    if (obfuscated) mixin { useLegacyMixinAp.set(false) }
    if (loader == "forge") forge { mixinConfig("minecraftauth.mixins.json") }
}

// each loader's entrypoint lives in its own package; compile only the one being built
kotlin.sourceSets.named("main") {
    listOf("fabric", "forge", "neoforge").filter { it != loader }.forEach { kotlin.exclude("me/minecraftauth/mod/$it/**") }
}

/** common + lib/jvm + their dependencies (Kotlin runtime included), shaded and relocated into the mod jar. */
val shade: Configuration = configurations.create("shade") {
    exclude("org.checkerframework")
    exclude("com.google.errorprone")
    exclude("org.jetbrains", "annotations")
}

// remapped (obfuscated targets) vs plain (26.x) configurations for loader dependencies
val modDependency = if (obfuscated) "modImplementation" else "implementation"

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    if (obfuscated) "mappings"(loom.officialMojangMappings())

    when (loader) {
        "fabric" -> {
            modDependency("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
            modDependency("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
        }
        "forge" -> "forge"("net.minecraftforge:forge:${property("forge_version")}")
        "neoforge" -> "neoForge"("net.neoforged:neoforge:${property("neoforge_version")}")
    }

    implementation(project(":minecraft:common"))
    shade(project(":minecraft:common"))
}

val relocations = listOf(
    "kotlin", "kotlinx", "dev.dejvokep.boostedyaml", "org.snakeyaml", "com.udojava.evalex", "com.github.benmanes.caffeine",
)

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations.set(listOf(shade))
    // obfuscated targets feed this into remapJar; 26.x ships it as-is
    archiveClassifier.set(if (obfuscated) "dev-shadow" else "")
    relocations.forEach { relocate(it, "me.minecraftauth.libs.$it") }
    mergeServiceFiles()
    // the versioned entries are stripped below, so the jar must not claim to be multi-release:
    // Forge 1.18-1.20's SecureJarHandler crashes on a Multi-Release jar without META-INF/versions
    addMultiReleaseAttribute.set(false)
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class", "META-INF/maven/**")
    exclude("DebugProbesKt.bin", "META-INF/jandex.idx", "META-INF/proguard/**", "META-INF/com.android.tools/**")
}

val finalJar: TaskProvider<out AbstractArchiveTask> = if (obfuscated) {
    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        inputFile.set(shadowJar.flatMap { it.archiveFile })
        dependsOn(shadowJar)
        archiveClassifier.set("")
    }
} else {
    tasks.named<Jar>("jar") { archiveClassifier.set("plain") }
    shadowJar
}
tasks.named("assemble") { dependsOn(finalJar) }

// `./gradlew modrinth` publishes every target; the version number (2.0.0+1.21.1-neoforge) is already unique per target
modrinth {
    uploadFile.set(finalJar)
    versionName.set("MinecraftAuth $baseVersion for " +
        loader.replaceFirstChar(Char::uppercaseChar).replace("Neoforge", "NeoForge") + " " + minecraftVersion)
    loaders.add(loader)
    // the same range the mod metadata declares
    gameVersions.set(minecraftReleases(minecraftRange))
    // only the dedicated server runs it (fabric.mod.json "environment": "server", side = SERVER)
    environment.set("DEDICATED_SERVER_ONLY")
    if (loader == "fabric") required.project("fabric-api")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft_range" to minecraftRange,
        "loader_range" to loaderRange,
        "java_version" to targetJava.toString(),
        // Fabric API's mod id was "fabric" before it became "fabric-api" (1.16.5 only has "fabric")
        "fabric_api_id" to (findProperty("fabric_api_id") ?: "fabric-api").toString(),
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) { expand(props) }

    // only ship the metadata of the loader being built
    if (loader != "fabric") exclude("fabric.mod.json")
    if (loader != "forge") exclude("META-INF/mods.toml")
    if (loader != "neoforge") exclude("META-INF/neoforge.mods.toml")
}

// collect every target's jar in minecraft/mod/build/libs
val collectJar = tasks.register<Copy>("collectJar") {
    from(finalJar)
    into(rootProject.layout.projectDirectory.dir("minecraft/mod/build/libs"))
    // the controller's clean empties that folder: in `clean build` it must not run after the copy
    mustRunAfter(":minecraft:mod:clean")
}
tasks.named("build") { finalizedBy(collectJar) }

// Runtime test: the shipped jar on a real dedicated server (see build-logic/src/main/kotlin/runtimetest/ModRuntimeTest.kt).
//   ./gradlew :minecraft:mod:1.20.1-forge:runtimeTest       one target
//   ./gradlew :minecraft:mod:runtimeTestAll                  every target, one server at a time
// -Pmcauth.runtimeTest.parallel=N runs N servers at once.
val serverSlots = gradle.sharedServices.registerIfAbsent("mcauthServerSlots", ServerSlots::class) {
    maxParallelUsages.set(providers.gradleProperty("mcauth.runtimeTest.parallel").map(String::toInt).orElse(1))
}

fun detached(notation: String, transitive: Boolean = false): Configuration =
    configurations.detachedConfiguration(dependencies.create(notation)).apply { isTransitive = transitive }

val targetLoader = loader
val targetMinecraft = minecraftVersion
tasks.register<ModRuntimeTest>("runtimeTest") {
    description = "Starts a real $targetLoader $targetMinecraft server with the built jar and checks the mod works"
    usesService(serverSlots)
    loader.set(targetLoader)
    minecraftVersion.set(targetMinecraft)
    platformName.set(mapOf("fabric" to "Fabric", "forge" to "Forge", "neoforge" to "NeoForge").getValue(targetLoader))
    expectedVersion.set(baseVersion)
    modJar.set(finalJar.flatMap { it.archiveFile })
    when (targetLoader) {
        "fabric" -> {
            fabricLoaderVersion.set(project.property("fabric_loader_version") as String)
            installer.from(detached("net.fabricmc:fabric-installer:${project.findProperty("fabric_installer_version") ?: "1.1.2"}"))
            // the fabric-api artifact is only the root module; the API modules are its dependencies
            extraMods.from(detached("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_api_version")}", transitive = true)
                .incoming.artifactView {
                    componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier && it.group == "net.fabricmc.fabric-api" }
                }.files)
        }
        "forge" -> installer.from(detached("net.minecraftforge:forge:${project.property("forge_version")}:installer"))
        "neoforge" -> installer.from(detached("net.neoforged:neoforge:${project.property("neoforge_version")}:installer"))
    }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(targetJava)) })
    serverDir.set(layout.buildDirectory.dir("runtimeTest/server"))
}
