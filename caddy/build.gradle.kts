// Caddy module (github.com/MinecraftAuthentication/integrations/caddy). `assemble` also builds a Caddy
// binary with the module built in, for local testing: build/go/bin/caddy
plugins {
    id("mcauth.go")
}

go {
    // lib/go is wired in through go.work and a replace directive
    extraSources.from(fileTree(rootDir.resolve("lib/go")) {
        include("**/*.go", "go.mod", "go.sum")
        exclude("build/**")
    })
    binaries.register("caddy") { pkg = "./cmd/caddy" }
}
