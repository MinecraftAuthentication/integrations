---
name: release
description: Release the MinecraftAuth integrations. Covers picking the version, finding which components changed, building, tagging, generating changelogs from the commits, and publishing lib/jvm (Maven), lib/go and caddy (Go module tags), lib/js (npm) and the Minecraft plugins, proxies and mods (Modrinth + GitHub release). Use when asked to release, publish, tag, ship or cut a version of any of them.
---

# Releasing the integrations

`RELEASING.md` is the human checklist and the source of truth. If this skill and `RELEASING.md` ever disagree,
follow `RELEASING.md` and tell the user about the mismatch.

## The model

- One version for the whole repository: `version` in `gradle.properties`.
- One release tag: `vX.Y.Z`.
- A release only publishes the components that changed since the previous `vX.Y.Z` tag. Unchanged components skip
  that version number.

| Component | Published by | Where |
|---|---|---|
| `lib_jvm` | `./gradlew :lib:jvm:publish` | Maven (`nexus.scarsz.me`) as `me.minecraftauth:lib:X.Y.Z` |
| `minecraft` | `./gradlew modrinth` + jars on the GitHub release | Modrinth as `X.Y.Z+<platform>` |
| `lib_go` | pushing the tag `lib/go/vX.Y.Z` | Go module proxy |
| `caddy` | pushing the tag `caddy/vX.Y.Z` | Go module proxy, caddyserver.com |
| `lib_js` | `npm publish` in `lib/js` | npm as `@minecraftauth/lib@X.Y.Z` |

Nothing publishes from CI. Every step below runs locally.

## Safety rules (read first)

- Local steps (builds, tests, the version bump, the commit, creating tags) need no confirmation.
- **Stop and get explicit confirmation from the user before each of these**, naming exactly what goes out:
  - pushing any tag or branch. The Go proxy caches tags forever, so never move, delete or force-push a pushed tag.
  - `./gradlew :lib:jvm:publish`
  - `npm publish`. An npm version number can never be reused, even after unpublishing.
  - `./gradlew modrinth`
  - `gh release create`
- One confirmation covers only the action it names.
- Never skip hooks or signing. Never use `--force`.
- If something is wrong after a tag was pushed, fix it forward with a new patch version.
- Never print credentials or token values. Only check whether they exist.

## 1. Preflight

Run these and report anything that fails. Don't work around a failure.

```sh
git switch main && git pull --ff-only
git status --porcelain                      # must be empty
git log --oneline main..origin/devel        # empty: devel is merged
gh run list --branch main --limit 5         # latest JVM / Go / JavaScript runs are green
grep '^version=' gradle.properties          # should be a -SNAPSHOT
```

Then check the credentials, but only for the components being released (step 2 decides which ones):

- **lib_jvm:** `grep -q '^scarszUsername=' ~/.gradle/gradle.properties && grep -q '^scarszPassword=' ~/.gradle/gradle.properties`
- **minecraft:** `[ -n "$MODRINTH_TOKEN" ]`, or `modrinth.token` in `~/.gradle/gradle.properties`
- **lib_js:** `npm whoami`
- **GitHub release:** `gh auth status`

## 2. Components and version

```sh
bash scripts/changed-components.sh          # component=true|false against the previous vX.Y.Z tag
prev=$(git describe --tags --abbrev=0 --match 'v[0-9]*' HEAD^ 2>/dev/null)
git log --oneline "$prev"..HEAD -- lib/jvm  # repeat per changed component path
```

The script ignores tests, `*.md` files and version bumps. It counts shipped dependencies, so a `lib/jvm` change marks
`minecraft` too. If there's no earlier tag, everything counts as changed.

Propose a version from what changed across the released components:

- a breaking change anywhere: major
- new features: minor
- fixes only: patch
- a `-alpha.N` / `-beta.N` / `-rc.N` suffix makes a prerelease

Confirm the version and the component list with the user. The user may add a component the script didn't flag.

## 3. Prepare (local)

```sh
./gradlew setVersion -PnewVersion=X.Y.Z     # gradle.properties + lib/js/package.json + package-lock.json
./gradlew clean build                       # add -Pmcauth.go.race=true if lib_go or caddy changed
```

`clean` matters: `minecraft/mod/build/libs` keeps old jars otherwise, and they'd get attached to the release.

Then run the pre-tag steps for each component being released.

**minecraft**
- Reset the Stonecutter tree and check it leaves no diff:
  `./gradlew ":minecraft:mod:Reset active project"`, then `git status --porcelain`.
- Run the mod runtime tests. They are the only runtime coverage (CI doesn't run them) and they're mandatory:
  `./gradlew :minecraft:mod:runtimeTestAll --continue`. It's slow, so run it in the background.
  `-Pmcauth.runtimeTest.parallel=2` runs two servers at a time. On failure, read
  `minecraft/mod/versions/<target>/build/runtimeTest/server/console.log`.
- Check the Modrinth metadata without uploading: `./gradlew modrinth -Pmodrinth.debug=true`.

**caddy**, if it needs a newer `lib/go`:
- `lib/go/vX.Y.Z` must be resolvable first. With confirmation, push that tag on its own.
- Then, in `caddy/`:
  - `go get github.com/MinecraftAuthentication/integrations/lib/go/v2@vX.Y.Z`
  - remove any `replace` line from `go.mod`
  - `go mod tidy`
- Rebuild: `./gradlew :caddy:build -Pmcauth.go.race=true`. These changes go into the release commit.

**lib_js**
- `cd lib/js && npm pack --dry-run`. Check that the file list is only `dist/`, `src/`, `README.md`, `LICENSE`
  and `package.json`. Nothing from `test/` or `node_modules/` should be in it.


## 4. Commit and tag

```sh
git commit -am "Release X.Y.Z"
git tag vX.Y.Z
git tag lib/go/vX.Y.Z                       # only if lib_go is released and not already tagged in step 3
git tag caddy/vX.Y.Z                        # only if caddy is released
```

With confirmation, push the commit and the tags:

```sh
git push origin main vX.Y.Z [lib/go/vX.Y.Z] [caddy/vX.Y.Z]
```

## 5. Changelogs

There's no changelog file in the repository: the commits are the changelog. Generate the changelogs from them now
that the tag exists, so their links point at the tag. `build/` is gitignored.

```sh
mkdir -p build
bash scripts/changelog.sh all vX.Y.Z > build/changelog.md                   # GitHub release notes
bash scripts/changelog.sh minecraft vX.Y.Z > build/changelog-minecraft.md   # Modrinth, if minecraft is released
```

- `changelog.sh` uses the same idea of a component's changes as `changed-components.sh`: tests, docs and the release
  commits are left out, and `lib/jvm` changes appear under Minecraft.
- Commit subjects are written for maintainers. Edit the files so each entry says what changed for users:
  reword entries, merge related ones, and drop internal-only ones (refactors, CI, build-only dependency bumps).
  Don't invent changes that aren't in the commits. Keep the commit links and the "Full changes" line.
- Show the user the edited changelogs and get their approval before publishing anything with them.

## 6. Publish

Publish only the released components, and ask for confirmation before each one.

Go in dependency order:
- `lib_jvm` before `minecraft`
- `lib_go` before `caddy`

First, check that you're on the tag and that it matches the version:

```sh
test "$(git describe --exact-match --tags --match 'v[0-9]*')" = "v$(sed -n 's/^version=//p' gradle.properties)"
```

**lib_jvm**
```sh
./gradlew :lib:jvm:publish
```

**lib_go** (the pushed tag is the release; check that it resolves):
```sh
go list -m github.com/MinecraftAuthentication/integrations/lib/go/v2@vX.Y.Z
```

**caddy**
- Check that a clean build works:
  `xcaddy build --with github.com/MinecraftAuthentication/integrations/caddy/v2@vX.Y.Z`
- On the first release only, the user registers the module by hand at
  https://caddyserver.com/account/register-package. Tell them to.

**lib_js**
```sh
cd lib/js && npm publish --access public    # prerelease: add --tag next so it doesn't become "latest"
```

**minecraft**
```sh
MODRINTH_CHANGELOG="$(cat build/changelog-minecraft.md)" ./gradlew modrinth
```
- This uploads every plugin, proxy and mod target. It refuses to upload without a changelog. Versions containing `alpha` become alpha, `beta`/`rc` beta.
- If it fails partway, check which targets are already on the Modrinth project before rerunning, because Modrinth
  rejects a duplicate version number.

**GitHub release** (every release):
```sh
gh release create vX.Y.Z --verify-tag --title "X.Y.Z" --notes-file build/changelog.md [--prerelease] \
  minecraft/plugin/*/build/libs/MinecraftAuth-*.jar minecraft/proxy/*/build/libs/MinecraftAuth-*.jar \
  minecraft/mod/build/libs/*.jar           # attach the jars only if minecraft is released
```

## 7. After

```sh
git switch devel && git merge --ff-only main
./gradlew setVersion -PnewVersion=<next>-SNAPSHOT
git commit -am "Back to <next>-SNAPSHOT"
```

- `<next>` is the next patch version: after X.Y.Z, use X.Y.(Z+1)-SNAPSHOT (2.1.0 → 2.1.1-SNAPSHOT).
- Push `devel` only with confirmation.
- Finish with a summary: what was published where, with links, and which components were skipped as unchanged.

## Gotchas

- On Windows (Git Bash), run scripts with `bash scripts/...`. `./gradlew` works as is.
- Publish only from the tagged commit. A tag that doesn't match `version=` means the wrong commit is checked out.
- `./gradlew modrinth` refuses `-SNAPSHOT` versions, and `./gradlew check` fails if `lib/js/package.json` has drifted
  from the project version (fix with `setVersion`).
- Go module tags need the directory prefix (`lib/go/`, `caddy/`). The `vX.Y.Z` tag alone doesn't release a Go
  module.
