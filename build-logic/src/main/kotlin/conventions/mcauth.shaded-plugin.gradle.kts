// Convention for a deployable Minecraft plugin jar: shades lib/jvm + minecraft/common and all of
// their dependencies (Kotlin stdlib included), relocated under me.minecraftauth.libs so they can't
// clash with other plugins or the server's own copies.
// The shaded jar is what `./gradlew modrinth` publishes; each plugin sets its Modrinth loaders and game versions.
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("mcauth.kotlin-jvm")
    id("com.gradleup.shadow")
    id("mcauth.modrinth")
}

val relocations = listOf(
    "kotlin",
    "kotlinx",
    "org.intellij.lang.annotations",
    "org.jetbrains.annotations",
    "dev.dejvokep.boostedyaml",
    "org.snakeyaml",
    "com.udojava.evalex",
    "com.github.benmanes.caffeine",
)

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("MinecraftAuth-" + project.name.replaceFirstChar(Char::uppercaseChar))
    relocations.forEach { relocate(it, "me.minecraftauth.libs.$it") }
    mergeServiceFiles()
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class", "META-INF/maven/**")
    exclude("DebugProbesKt.bin", "META-INF/jandex.idx", "META-INF/proguard/**", "META-INF/com.android.tools/**")
    // compile-time annotations pulled in by Caffeine
    exclude("org/checkerframework/**", "com/google/errorprone/**")
}

tasks.named<Jar>("jar") { archiveClassifier.set("plain") }
tasks.named("assemble") { dependsOn("shadowJar") }

modrinth {
    uploadFile.set(tasks.named("shadowJar"))
    // every plugin shares one Modrinth project and one version: tell their versions apart (2.0.0+bukkit)
    versionNumber.set(provider { "${project.version}+${project.name}" })
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "bungee.yml", "velocity-plugin.json", "META-INF/sponge_plugins.json")) { expand(props) }
}
