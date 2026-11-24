// Builds and tests a Go module (the project directory holds its go.mod) with the Go toolchain:
//   goBuild  `go build ./...`           -> assemble
//   go{Bin}  each binary in go.binaries -> assemble, written to build/go/bin
//   goVet    `go vet ./...`             -> check
//   goTest   `go test -cover ./...`     -> check, coverage profile in build/go/coverage.out
//
// Properties: mcauth.go.executable (default "go"), mcauth.go.race (default false; needs a C compiler).
import go.GoExec
import go.GoExtension

plugins {
    base
}

val go = extensions.create<GoExtension>("go").apply {
    race.convention(providers.gradleProperty("mcauth.go.race").map(String::toBoolean).orElse(false))
}

val goExecutable = providers.gradleProperty("mcauth.go.executable").orElse("go")
val goEnv = providers.exec {
    commandLine(goExecutable.get(), "env", "GOVERSION", "GOOS", "GOARCH", "GOEXE", "GOFLAGS")
}.standardOutput.asText.map { it.trim() }
// executable suffix (".exe" on Windows); the 4th line, absent when it and GOFLAGS are both empty
val goExeSuffix = goEnv.map { it.lines().getOrElse(3) { "" } }

val goSources = files(
    fileTree(projectDir) {
        include("**/*.go", "**/go.mod", "**/go.sum", "**/testdata/**")
        exclude("build/**", ".gradle/**")
    },
    rootProject.files("go.work", "go.work.sum").filter { it.exists() },
    go.extraSources,
)
val goOut = layout.buildDirectory.dir("go")

tasks.withType<GoExec>().configureEach {
    group = "go"
    executable.set(goExecutable)
    toolchain.set(goEnv)
    workingDir.set(layout.projectDirectory)
    sources.from(goSources)
    log.convention(goOut.map { it.file("$name.log") })
}

val goBuild = tasks.register<GoExec>("goBuild") {
    description = "Compiles every package in the module."
    args.set(listOf("build", "./..."))
}

val goVet = tasks.register<GoExec>("goVet") {
    description = "Runs go vet."
    args.set(listOf("vet", "./..."))
}

val goTest = tasks.register<GoExec>("goTest") {
    description = "Runs the Go tests with coverage."
    val coverage = goOut.map { it.file("coverage.out") }
    products.from(coverage)
    args.set(go.race.zip(coverage) { race, profile ->
        listOf("test") + (if (race) listOf("-race") else emptyList()) +
            listOf("-cover", "-coverprofile=${profile.asFile.absolutePath}", "./...")
    })
    environment.set(go.race.map { if (it) mapOf("CGO_ENABLED" to "1") else emptyMap() })
    shouldRunAfter(goVet)
}

go.binaries.all {
    val binary = this
    val task = tasks.register<GoExec>("go" + binary.name.replaceFirstChar(Char::uppercase)) {
        description = "Builds the ${binary.name} binary."
        val exe = goExeSuffix.zip(goOut) { suffix, dir -> dir.file("bin/${binary.name}$suffix") }
        products.from(exe)
        args.set(exe.zip(binary.pkg) { file, pkg -> listOf("build", "-o", file.asFile.absolutePath, pkg) })
    }
    tasks.named("assemble") { dependsOn(task) }
}

tasks.named("assemble") { dependsOn(goBuild) }
tasks.named("check") { dependsOn(goVet, goTest) }
