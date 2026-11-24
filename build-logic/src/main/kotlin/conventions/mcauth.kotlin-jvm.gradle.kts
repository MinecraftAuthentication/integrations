// Kotlin/JVM convention for MinecraftAuth integrations.
// Libraries default to Java 8 bytecode so they run everywhere (Forge 1.16.5, old Spigot);
// a module can raise it with `mcauth { javaVersion = 17 }` *before* compilation is configured.
import conventions.McAuthExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val mcauth = extensions.create<McAuthExtension>("mcauth").apply { javaVersion.convention(8) }

java {
    toolchain.languageVersion.set(mcauth.javaVersion.map(JavaLanguageVersion::of))
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(mcauth.javaVersion.map { if (it == 8) JvmTarget.JVM_1_8 else JvmTarget.fromTarget(it.toString()) })
        freeCompilerArgs.addAll("-jvm-default=enable", "-Xsuppress-version-warnings")
    }
}

dependencies {
    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(catalog.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    manifest.attributes(
        "Implementation-Title" to project.name,
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to "MinecraftAuth.me",
    )
}
