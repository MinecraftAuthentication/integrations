# Contributing

Thanks for helping out. Bug reports and feature requests go in
[GitHub issues](https://github.com/MinecraftAuthentication/integrations/issues).

## Setting up

You need:

- **JDK 25** to run Gradle (Stonecutter requires it). Gradle downloads the other JDKs the targets need (8, 17, 21).
- **Go 1.26+** for `lib/go` and `caddy`.
- **Node.js 22+** and npm for `lib/js`.

You only need the toolchains for the parts you touch. `./gradlew build` builds and tests everything. The
[root README](README.md#building) lists the per-project tasks and how to build without Gradle.

## Pull requests

- Open pull requests against `devel`.
- Keep one change per pull request, and add tests for it. CI builds and tests everything (JVM, Go and JavaScript)
  on every commit and pull request.
- **Mods:** if you switched the Stonecutter tree to another target, run `./gradlew ":minecraft:mod:Reset active project"`
  before committing, or `src/` gets committed in that target's form.
- If you changed a mod or a platform adapter, run that target's `runtimeTest`
  (`./gradlew :minecraft:mod:1.21.1-neoforge:runtimeTest`).
- Update the docs a user would read: `minecraft/README.md`, `caddy/README.md`, the bundled `MinecraftAuth.yml`
  comments, and the library READMEs.

## Where things live

| Path | |
|---|---|
| `lib/jvm`, `lib/go`, `lib/js` | API clients. Keep them in step: a new endpoint goes into all three |
| `minecraft/common` | Config (`Settings`), gatekeeper and `Conditions`, shared by every Minecraft integration |
| `minecraft/plugin`, `minecraft/proxy` | Thin platform adapters around `minecraft/common` |
| `minecraft/mod` | The Stonecutter tree for Fabric/Forge/NeoForge; see [minecraft/README.md](minecraft/README.md#mods-stonecutter) |
| `caddy` | Caddy module; realm rules are evaluated in `realm.go` |
| `build-logic` | Gradle convention plugins, the Go/npm task wrappers, the runtime test harness |

## Adding a new condition

The same set of rules exists twice: as functions in Kotlin `Conditions.kt` (every Minecraft integration) and as
realm rules in the Caddy module. Add a new condition to both, with the same meaning, or document why it only makes
sense on one side.

| Minecraft (`Conditions.kt`) | Caddy (`realm.go`) |
|---|---|
| `TwitchFollower()` | `twitch_follower` |
| `TwitchSubscriber([tier])` | `twitch_subscriber [tier]` |
| `YouTubeSubscriber()` | `youtube_subscriber` |
| `YouTubeMember()` | `youtube_member` |
| `PatreonMember(["tier"])` | `patreon_member [tier]` |
| `DiscordServer("guild")` | `discord_server <guild>` |
| `DiscordRole("role id")`, `DiscordRole("guild", "role id or name")` | `discord_role <role id>`, `discord_role <guild> <role id or name>` |
| `Linked(["platform"])` | `linked` (no platform argument) |
| (bypass list) | `player <uuid or name>...` |
| | `permission`, `group` (LuckPerms) |

### 1. API clients (only when the condition needs an endpoint the clients don't call yet)

- [ ] `lib/jvm`: the method in `MinecraftAuth.kt` plus its `…Async` variant; a test in `MinecraftAuthTest.kt`
      (via `FakeApi`).
- [ ] `lib/go`: the method in `client.go`; a test in `client_test.go`.
- [ ] `lib/js`: the method in `src/client.ts`; a Vitest test.
- [ ] The library READMEs, if the method is public.

### 2. Minecraft (`minecraft/common`)

- [ ] If it needs a new kind of API call, add it to the `Membership` interface and `ApiMembership` in `Membership.kt`.
- [ ] Add a `BooleanFunction` in `Conditions.functions()`. The second argument is the parameter count (`-1` = any).
- [ ] Validate the arguments **outside** the `cached { ... }` block, throwing `Expression.ExpressionException`.
      `validate()` runs each function once with an answer function that never calls the API. That's how a bad
      condition gets rejected when the config loads instead of when a player joins.
- [ ] Give it a cache key with its own prefix that includes the player and every normalized argument. Lowercase an
      argument the API matches ignoring case, so `"Gold"` and `"gold"` share an entry.
- [ ] Anything that can be confused across servers (Discord role names, for example) must be scoped to an explicit
      ID. Never accept the ambiguous form.
- [ ] Tests in `GatekeeperTest.kt`. If you added a `Membership` method, implement it in `FakeMembership`.
- [ ] Document it in the "Available conditions" comment in `minecraft/common/src/main/resources/MinecraftAuth.yml`
      and in the condition table in `minecraft/README.md`.

The plugins, proxies and mods need no changes. They all evaluate conditions through `minecraft/common`.

### 3. Caddy (`caddy`)

- [ ] Add the rule's minimum and maximum number of values to `ruleArity` in `app.go`. The Caddyfile parser rejects
      rule types that aren't in it.
- [ ] Add any extra validation to `validateRule` in `app.go` (see `discord_role` and `twitch_subscriber`).
- [ ] Add the API call to `checkMembership` in `realm.go`. A rule decided without the API (like `player`) goes in
      `matchLocal` and in the `case` of `needsAPI` instead.
- [ ] Add a row to `TestMembershipEndpoints` in `realm_test.go`. `TestEveryAPIRuleHasAnEndpoint` fails if
      `ruleArity` has a rule that `checkMembership` doesn't handle.
- [ ] Add a Caddyfile parse test in `caddyfile_test.go` if the rule takes arguments.
- [ ] Document it in the `Rule` doc comment (`app.go`), the Caddyfile syntax comment (`caddyfile.go`), the
      "Realm rules" table in `caddy/README.md`, and an example in `caddy/examples/` when it's worth one.

### 4. Finally

- [ ] Both sides normalize arguments the same way. For example, a Twitch tier of `1000` becomes `1` in both
      `normalizeTier` functions.

## Dependencies

Dependabot opens weekly update pull requests for Gradle, Go, npm and GitHub Actions (see
[`.github/dependabot.yml`](.github/dependabot.yml)). Some versions are pinned on purpose, and Dependabot is told to
leave them alone:

- **Caffeine stays on 2.x.** 3.x needs Java 11, and `minecraft/common` targets Java 8 (Forge 1.16.5).
- **`@types/node` stays on the oldest supported Node major** (`engines` in `lib/js/package.json`), so the code
  can't use newer Node APIs.
- **The Spigot, Velocity, BungeeCord and Sponge APIs** are the oldest versions supported, so the plugins load on
  them.
- **Mod targets** (`minecraft/mod/versions/*/gradle.properties`) pin each Minecraft version's loader and mappings.
  Update them by hand when adding or refreshing a target.
