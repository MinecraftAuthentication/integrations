// Root of the MinecraftAuthentication/integrations build.
// Go modules (lib/go, caddy) are Gradle projects too: the mcauth.go convention drives the Go toolchain.
plugins {
    base
    // load build-logic (and the Kotlin Gradle plugin it brings) once, in the root classloader
    id("mcauth.kotlin-jvm") apply false
}

// One version for the whole repository (`version` in gradle.properties); see RELEASING.md
val projectVersion = providers.gradleProperty("version").get()

allprojects {
    group = providers.gradleProperty("group").get()
    version = projectVersion
}

// `./gradlew setVersion -PnewVersion=X.Y.Z` sets the version everywhere it's written down: gradle.properties, and
// lib/js/package.json + package-lock.json (npm needs it there; :lib:js:checkPackageVersion keeps them in step).
tasks.register("setVersion") {
    group = "release"
    description = "Sets the project version (-PnewVersion=X.Y.Z) in gradle.properties and lib/js."
    val newVersion = providers.gradleProperty("newVersion")
    val properties = layout.projectDirectory.file("gradle.properties").asFile
    val packageFiles = listOf("package.json", "package-lock.json").map { layout.projectDirectory.file("lib/js/$it").asFile }
    doLast {
        val version = newVersion.orNull?.trim()?.removePrefix("v")
            ?: throw GradleException("Pass the new version: ./gradlew setVersion -PnewVersion=X.Y.Z")
        if (!Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""").matches(version)) {
            throw GradleException("'$version' isn't a semantic version (X.Y.Z or X.Y.Z-suffix)")
        }

        val versionLine = Regex("""(?m)^version=.*$""")
        val text = properties.readText()
        if (!versionLine.containsMatchIn(text)) throw GradleException("No version= line in $properties")
        properties.writeText(text.replaceFirst(versionLine, "version=$version"))

        // package.json: the top-level "version"; package-lock.json: the top-level one and packages[""]'s.
        // Both are the first "version" key at their indentation, ahead of any dependency's.
        packageFiles.forEach { file ->
            val indents = if (file.name == "package-lock.json") listOf("  ", "      ") else listOf("  ")
            var json = file.readText()
            indents.forEach { indent ->
                val key = Regex("""(?m)^$indent"version": "[^"]*"""")
                if (!key.containsMatchIn(json)) throw GradleException("No \"version\" at indent ${indent.length} in $file")
                json = json.replaceFirst(key, "$indent\"version\": \"$version\"")
            }
            file.writeText(json)
        }
        logger.lifecycle("Version set to $version")
    }
}

