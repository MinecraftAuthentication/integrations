# Releasing

The whole repository has **one version**, `version` in `gradle.properties`, and every release is one tag, `vX.Y.Z`,
following [semantic versioning](https://semver.org) for the project as a whole. Not every component changes in every
release, and that's fine: a release only publishes the components that changed since the previous `vX.Y.Z` tag. An
unchanged component simply has no X.Y.Z, so a component's versions can skip numbers (`lib/go` might go 2.0.0 → 2.3.0).

| Component | Published as | Where |
|---|---|---|
| `lib/jvm` | `me.minecraftauth:lib:X.Y.Z` | Maven (`nexus.scarsz.me`) |
| Minecraft plugins, proxies, mods | `X.Y.Z+<platform>` | GitHub release jars, Modrinth |
| `lib/go` | tag `lib/go/vX.Y.Z` | Go module proxy |
| `caddy` | tag `caddy/vX.Y.Z` | Go module proxy, caddyserver.com |
| `lib/js` | `@minecraftauth/lib@X.Y.Z` | npm |

Go only finds a submodule's versions through tags prefixed with its directory, so `lib/go` and `caddy` get their own
tag, normally on the same commit as `vX.Y.Z`, when they're released.

## Which components to publish

```sh
bash scripts/changed-components.sh
```

compares `HEAD` with the previous `vX.Y.Z` tag and prints `component=true` for each component to publish. It ignores
tests, `*.md` files and the version bump itself, and counts a component's shipped dependencies: a `lib/jvm` change
also marks `minecraft`, since every plugin, proxy and mod jar shades it. Shared build inputs (`gradle/libs.versions.toml`,
the JVM conventions in `build-logic`) mark the JVM components. A component can always be published anyway when there's
a reason the script can't see.

Bump the version by what changed across the released components: a breaking change in any of them is a major release.

## Changelogs

There's no changelog file: the commits are the changelog. `scripts/changelog.sh` turns the commits that changed a
component since the previous release into a Markdown list, using the same definition of "changed" as above (so a
`lib/jvm` fix shows up in the Minecraft changelog too), with links to each commit and the full comparison:

```sh
bash scripts/changelog.sh minecraft vX.Y.Z > build/changelog-minecraft.md   # one component (Modrinth)
bash scripts/changelog.sh all vX.Y.Z > build/changelog.md                   # every changed component (GitHub release)
```

Generate them after tagging, so the links point at the tag. Commit subjects are written for maintainers: before
publishing, read the generated file and reword or drop entries that mean nothing to users (dependency bumps,
refactors). `build/` is gitignored, so the files don't get committed.

## Every release

- [ ] `devel` is green in CI and merged into `main`. Release from `main`.
- [ ] `./gradlew setVersion -PnewVersion=X.Y.Z` (no `-SNAPSHOT`; `-alpha`/`-beta`/`-rc` suffixes make Modrinth
      alpha/beta versions). It updates `gradle.properties` and `lib/js/package.json` + `package-lock.json`.
- [ ] `bash scripts/changed-components.sh` and note which components to publish.
- [ ] `./gradlew build` (add `-Pmcauth.go.race=true` when `lib/go` or `caddy` changed).
- [ ] Do the component steps below that need doing *before* the tag (`caddy`'s `go.mod`, the Minecraft checks).
- [ ] Commit (`Release X.Y.Z`), tag `vX.Y.Z`, plus `lib/go/vX.Y.Z` and/or `caddy/vX.Y.Z` if those are being released,
      and push the tags.
- [ ] Publish each changed component (below).
- [ ] Publish a GitHub release for `vX.Y.Z` with `scripts/changelog.sh all vX.Y.Z` as the notes. If the Minecraft
      integrations changed, attach the plugin, proxy and mod jars.
- [ ] On `devel`: `./gradlew setVersion -PnewVersion=<next>-SNAPSHOT` and commit.

Between releases, every successful build of `devel` is published as the `snapshot` GitHub pre-release
(`.github/workflows/snapshot.yml`), with the plugin, proxy and mod jars. CI deletes and recreates that release and
moves the `snapshot` tag each time, so don't use `snapshot` as a tag or release name for anything else.

Release order matters where components depend on each other: `lib/go` before `caddy` (caddy's `go.mod` pins a
released `lib/go`), `lib/jvm` before the Minecraft integrations.

## lib/jvm

- [ ] `./gradlew :lib:jvm:publish` with the `scarszUsername` / `scarszPassword` Gradle properties set
      (e.g. in `~/.gradle/gradle.properties`).

## Minecraft integrations

Before tagging:

- [ ] Make sure the Stonecutter tree is reset: `./gradlew ":minecraft:mod:Reset active project"` leaves no diff.
- [ ] `./gradlew :minecraft:mod:runtimeTestAll --continue`. CI doesn't run it, so it has to pass locally.
- [ ] Check the Modrinth metadata without uploading anything: `./gradlew modrinth -Pmodrinth.debug=true`.

After tagging:

- [ ] From the tagged commit (`git describe --exact-match` prints `vX.Y.Z`), upload every plugin, proxy and mod jar:
      `MODRINTH_CHANGELOG="$(cat build/changelog-minecraft.md)" ./gradlew modrinth` (generated with
      `scripts/changelog.sh minecraft vX.Y.Z`, see [Changelogs](#changelogs)), with `MODRINTH_TOKEN` set (a token with
      the "Create versions" scope) or `-Pmodrinth.token`. It refuses to upload without a changelog.
      If it fails partway, check the Modrinth project for the targets already uploaded before rerunning: Modrinth
      rejects a version number it already has.

## lib/go

- [ ] After pushing `lib/go/vX.Y.Z`: check that
      `go list -m github.com/MinecraftAuthentication/integrations/lib/go@vX.Y.Z` resolves it.

## caddy

Before tagging:

- [ ] If `caddy` needs a newer `lib/go` (released in this or an earlier release): in `caddy/`,
      `go get github.com/MinecraftAuthentication/integrations/lib/go@vX.Y.Z`, remove the `replace` line from `go.mod`
      if it's still there, and `go mod tidy`. `go.work` still points local builds at `../lib/go`.
      Releasing `lib/go` and `caddy` together needs `lib/go/vX.Y.Z` pushed first, so `go get` can resolve it: push that
      tag, update `caddy/go.mod`, commit, and only then tag `vX.Y.Z` and `caddy/vX.Y.Z` on that commit.
- [ ] `./gradlew :caddy:build -Pmcauth.go.race=true`

After tagging:

- [ ] Check that a clean build works:
      `xcaddy build --with github.com/MinecraftAuthentication/integrations/caddy@vX.Y.Z`.
- [ ] First release only: register the module at
      [caddyserver.com/account/register-package](https://caddyserver.com/account/register-package) so it's listed on
      the download page. Later versions are picked up from the tags.

## lib/js

- [ ] `npm pack --dry-run` in `lib/js` and check the file list.
- [ ] `npm publish --access public` from `lib/js` (`prepack` builds `dist`). For a prerelease add `--tag next`, so
      it doesn't become `latest`. `./gradlew check` fails if
      `package.json` doesn't carry the project version, so it can't drift from `gradle.properties`.
