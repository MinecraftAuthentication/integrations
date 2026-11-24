// Builds and tests an npm package (the project directory holds its package.json) with npm scripts:
//   npmCi         `npm ci`                -> installs node_modules from package-lock.json (never build-cached)
//   npmTypecheck  `npm run typecheck`     -> check
//   npmTest       `npm test`              -> check
//   npmBuild      `npm run build`         -> assemble, output in dist/
//
// Properties: mcauth.npm.executable (default "npm", "npm.cmd" on Windows), mcauth.node.executable (default "node").
import node.NpmExec

plugins {
    base
}

val windows = System.getProperty("os.name").startsWith("Windows")
val npmExecutable = providers.gradleProperty("mcauth.npm.executable").orElse(if (windows) "npm.cmd" else "npm")
val nodeExecutable = providers.gradleProperty("mcauth.node.executable").orElse("node")
val nodeVersion = providers.exec {
    commandLine(nodeExecutable.get(), "--version")
}.standardOutput.asText.map { it.trim() }

val manifest = files("package.json", "package-lock.json")
val nodeSources = files(
    manifest,
    fileTree(projectDir) {
        include("src/**", "test/**", "tsconfig*.json", "*.config.*")
        exclude("node_modules/**", "dist/**", "build/**")
    },
)
val nodeOut = layout.buildDirectory.dir("node")
// npm writes this "hidden lockfile" on every install, so its absence means node_modules is gone
val installed = layout.projectDirectory.file("node_modules/.package-lock.json")

tasks.withType<NpmExec>().configureEach {
    group = "node"
    executable.set(npmExecutable)
    toolchain.set(nodeVersion)
    workingDir.set(layout.projectDirectory)
    log.convention(nodeOut.map { it.file("$name.log") })
}

val npmCi = tasks.register<NpmExec>("npmCi") {
    description = "Installs the exact dependencies from package-lock.json."
    args.set(listOf("ci", "--no-audit", "--no-fund"))
    sources.from(manifest)
    products.from(installed)
    // restoring the log and marker from the cache wouldn't restore node_modules
    outputs.doNotCacheIf("node_modules lives outside the build cache") { true }
}

fun NpmExec.npmScript(vararg script: String) {
    args.set(script.toList())
    sources.from(nodeSources, installed)
    dependsOn(npmCi)
}

val npmTypecheck = tasks.register<NpmExec>("npmTypecheck") {
    description = "Type-checks the sources and tests."
    npmScript("run", "typecheck")
}

val npmTest = tasks.register<NpmExec>("npmTest") {
    description = "Runs the tests."
    npmScript("test")
    shouldRunAfter(npmTypecheck)
}

val npmBuild = tasks.register<NpmExec>("npmBuild") {
    description = "Compiles the package into dist/."
    npmScript("run", "build")
    productDirs.from(layout.projectDirectory.dir("dist"))
}

tasks.named("assemble") { dependsOn(npmBuild) }
tasks.named("check") { dependsOn(npmTypecheck, npmTest) }
tasks.named<Delete>("clean") { delete("dist") }
