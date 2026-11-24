# MinecraftAuth integrations

Ready-made integrations for [MinecraftAuth.me](https://minecraftauth.me), where players link their
Minecraft account to Discord, Twitch, Patreon and YouTube. Everything here uses the
[v2 API](https://minecraftauth.me/developers/docs/api-reference).

| Integration | Path | What it does |
|---|---|---|
| **Minecraft server plugins** | [`minecraft/plugin`](minecraft/README.md) | Bukkit/Spigot/Paper/Folia and Sponge plugins that only let in players who meet your conditions (Twitch follower, Discord role, Patreon tier, ...) |
| **Minecraft proxy plugins** | [`minecraft/proxy`](minecraft/README.md) | The same for Velocity and BungeeCord, with optional per-backend-server rules |
| **Minecraft mods** | [`minecraft/mod`](minecraft/README.md) | Fabric, Forge and NeoForge server mods from one [Stonecutter](https://stonecutter.kikugie.dev) source tree, Minecraft 1.16.5 to 26.x |
| **Caddy** | [`caddy`](caddy/README.md) | A [Caddy](https://caddyserver.com) module that puts sites and routes behind a MinecraftAuth.me login and only lets chosen players through |
| JVM client | [`lib/jvm`](lib/jvm/README.md) | `me.minecraftauth:lib`, the Kotlin/Java v2 API client the Minecraft integrations use |
| Go client | [`lib/go`](lib/go/README.md) | `github.com/MinecraftAuthentication/integrations/lib/go`, the Go v2 API client the Caddy module uses |
| JavaScript/TypeScript client | [`lib/js`](lib/js/README.md) | `@minecraftauth/lib` on npm, a dependency-free v2 API client for Node.js, Deno, Bun, edge runtimes and browsers |

This project isn't associated with or endorsed by Minecraft/Mojang.

## Building

**JVM** (`lib/jvm`, `minecraft/*`): Gradle runs on Java 25 (Stonecutter requires it). The other JDKs
each target needs (8, 17, 21, 25) are downloaded automatically.

```sh
./gradlew build                        # everything, every mod target included (+ tests)
./gradlew :minecraft:mod:buildAll      # only the mods, every target -> minecraft/mod/build/libs
```

Plugin and proxy jars end up in `minecraft/<plugin|proxy>/<name>/build/libs/MinecraftAuth-<Name>-<version>.jar`.

**Go** (`lib/go`, `caddy`): Go 1.26+ on the PATH (or `-Pmcauth.go.executable=...`). A `go.work` file ties
the two modules together. They are Gradle projects too, so `./gradlew build` vets and tests them; the plain
Go commands work as well.

```sh
./gradlew :lib:go:build :caddy:build   # go build + vet + test; Caddy binary -> caddy/build/go/bin
./gradlew :caddy:goTest -Pmcauth.go.race=true   # with the race detector (needs a C compiler)
(cd caddy && go test ./...)            # without Gradle
```

Coverage profiles land in `<module>/build/go/coverage.out` (`go tool cover -html=build/go/coverage.out`).

**JavaScript** (`lib/js`): Node.js 22+ and npm on the PATH (or `-Pmcauth.node.executable=...` /
`-Pmcauth.npm.executable=...`). It's a Gradle project too, so `./gradlew build` installs, type-checks, tests and
compiles it. The plain npm scripts work as well.

```sh
./gradlew :lib:js:build                # npm ci + typecheck + test + build -> lib/js/dist
(cd lib/js && npm ci && npm test)      # without Gradle
```

## Gradle tasks

A task name without a project path (`./gradlew build`) runs in every project that has it.

### Whole build

| Task | What it does |
|---|---|
| `build` | Compiles, tests and assembles every project: JVM, Go and every mod target |
| `assemble` / `check` | Just the jars and binaries / just the tests and `go vet` |
| `modrinth` | Publishes every plugin, proxy and mod jar to Modrinth (see [Publishing](#publishing)) |

### JVM (`lib/jvm`, `minecraft/common`, plugins, proxies)

| Task | What it does |
|---|---|
| `:lib:jvm:build` | Builds and tests the API client |
| `:lib:jvm:publishToMavenLocal` | Installs `me.minecraftauth:lib` into `~/.m2` |
| `:lib:jvm:publish` | Publishes `me.minecraftauth:lib` to `nexus.scarsz.me` (needs `scarszUsername` / `scarszPassword` Gradle properties) |
| `:minecraft:plugin:bukkit:build` (`sponge`, `:minecraft:proxy:velocity`, `bungee`) | Builds one plugin's shaded jar -> `build/libs/MinecraftAuth-<Name>-<version>.jar` |
| `:minecraft:plugin:bukkit:shadowJar` | Only the shaded jar, without running tests |
| `:minecraft:plugin:bukkit:modrinth` | Publishes one plugin to Modrinth |
| `test` | JUnit 5 tests of a project |

### Mods (`minecraft/mod`)

Each target is its own project, `:minecraft:mod:<minecraft>-<loader>` (the list is in `settings.gradle.kts`).
See [minecraft/README.md](minecraft/README.md) for working on the Stonecutter tree.

| Task | What it does |
|---|---|
| `:minecraft:mod:buildAll` | Builds every target; jars are collected in `minecraft/mod/build/libs` |
| `:minecraft:mod:1.20.1-forge:build` | Builds one target |
| `:minecraft:mod:1.21.1-neoforge:runServer` | Starts a development server for one target |
| `:minecraft:mod:1.20.1-forge:runtimeTest` | Runs one target's shipped jar on a real dedicated server and checks it loads, answers commands and gates logins |
| `:minecraft:mod:runtimeTestAll` | `runtimeTest` for every target (add `--continue` to see every failure) |
| `":minecraft:mod:Set active project to 26.3-fabric"` | Rewrites `src/` for that target, so the IDE sees its code |
| `":minecraft:mod:Reset active project"` | Switches `src/` back to the VCS version; run it before committing |
| `:minecraft:mod:1.20.1-forge:modrinth` | Publishes one target to Modrinth |

### Go (`lib/go`, `caddy`)

| Task | What it does |
|---|---|
| `:lib:go:build`, `:caddy:build` | `goBuild` + `goVet` + `goTest` |
| `:caddy:goCaddy` | Builds a Caddy binary with the module built in -> `caddy/build/go/bin/caddy` |
| `goBuild` | `go build ./...` |
| `goVet` | `go vet ./...` |
| `goTest` | `go test -cover ./...`, coverage profile in `build/go/coverage.out` |

The output of each Go task is logged to `<module>/build/go/<task>.log`.

### JavaScript (`lib/js`)

| Task | What it does |
|---|---|
| `:lib:js:build` | `npmCi` + `npmTypecheck` + `npmTest` + `npmBuild` |
| `npmCi` | `npm ci` (only when `package.json`/`package-lock.json` change or `node_modules` is missing) |
| `npmTypecheck` | `npm run typecheck` (`tsc --noEmit` over sources and tests) |
| `npmTest` | `npm test` (Vitest) |
| `npmBuild` | `npm run build`, compiled ESM + type definitions in `lib/js/dist` |

The output of each npm task is logged to `lib/js/build/node/<task>.log`.

### Properties

| Property | Default | Used by |
|---|---|---|
| `-Pmcauth.go.executable=<path>` | `go` | Go tasks: the Go toolchain to run |
| `-Pmcauth.go.race=true` | `false` | `goTest`: race detector (needs a C compiler) |
| `-Pmcauth.node.executable=<path>` | `node` | npm tasks: the Node.js whose version keys the task cache |
| `-Pmcauth.npm.executable=<path>` | `npm` (`npm.cmd` on Windows) | npm tasks: the npm to run |
| `-Pmcauth.runtimeTest.parallel=N` | `1` | `runtimeTest`: how many servers may run at once |
| `-Pversion=X.Y.Z` | `gradle.properties` | Version of everything (libraries, plugins, proxies, mods) |
| `-PnewVersion=X.Y.Z` | | `setVersion`: writes the version to `gradle.properties` and `lib/js/package.json` |
| `MODRINTH_TOKEN` (env) or `-Pmodrinth.token` | | `modrinth`: token with the "Create versions" scope |
| `MODRINTH_CHANGELOG` (env) or `-Pmodrinth.changelog` | | `modrinth`: Markdown changelog, required to upload (`scripts/changelog.sh minecraft`) |
| `-Pmodrinth.debug=true` | `false` | `modrinth`: log the upload requests instead of sending them |
| `-Pmodrinth_project=<slug>` | `gradle.properties` | `modrinth`: the Modrinth project to publish to |

### Publishing

`./gradlew modrinth` uploads every plugin, proxy and mod jar as a version of the Modrinth project. Versions containing `alpha` become alpha, `beta`/`rc` beta, anything else a
release; `-SNAPSHOT` versions are refused. It's run by hand during a release, when the Minecraft integrations
changed (see [RELEASING.md](RELEASING.md)).

## Layout

```
lib/jvm             Kotlin API client (me.minecraftauth:lib)
lib/go              Go API client (package mcauth)
lib/js              JavaScript/TypeScript API client (@minecraftauth/lib)
minecraft/common    config, gatekeeper and conditions shared by every Minecraft integration
minecraft/plugin    bukkit (Spigot/Paper/Folia), sponge
minecraft/proxy     velocity, bungee
minecraft/mod       Stonecutter tree: versions/<minecraft>-<loader>/gradle.properties per target
caddy               Caddy module (http.handlers.mcauth / mcauth_callback, app mcauth)
build-logic         Gradle convention plugins
```

## Releasing

The repository has one version (`version` in `gradle.properties`) and one release tag, `vX.Y.Z`. A release only
publishes the components that changed since the previous one (`bash scripts/changed-components.sh` lists them), so
a component's versions may skip numbers. The Go modules also get `lib/go/vX.Y.Z` / `caddy/vX.Y.Z` tags when they're
released, which Go needs to find them. The steps are in [RELEASING.md](RELEASING.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), including the checklist for adding a new condition.

## License

[Apache 2.0](LICENSE)
