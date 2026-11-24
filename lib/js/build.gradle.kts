// JavaScript/TypeScript client for the MinecraftAuth.me API (@minecraftauth/lib on npm).
plugins {
    id("mcauth.node")
}

// npm reads the version from package.json; it has to match the project version (`./gradlew setVersion` sets both)
val checkPackageVersion = tasks.register("checkPackageVersion") {
    group = "verification"
    description = "Checks that package.json and package-lock.json carry the project version."
    val expected = project.version.toString()
    val manifests = files("package.json", "package-lock.json")
    inputs.property("version", expected)
    inputs.files(manifests)
    doLast {
        manifests.forEach { file ->
            val found = Regex("""(?m)^  "version": "([^"]*)"""").find(file.readText())?.groupValues?.get(1)
            if (found != expected) {
                throw GradleException(
                    "${file.name} has version $found but the project version is $expected; " +
                        "run ./gradlew setVersion -PnewVersion=$expected"
                )
            }
        }
    }
}
tasks.named("check") { dependsOn(checkPackageVersion) }
