# MinecraftAuth for Minecraft servers

Only let players join who follow you on Twitch, subscribe on YouTube, back you on Patreon or are in
your Discord server. Players link their accounts once at [minecraftauth.me](https://minecraftauth.me).

| Platform | Jar | Hook |
|---|---|---|
| Spigot / Paper / Folia (1.16.5+) | `MinecraftAuth-Bukkit` | `AsyncPlayerPreLoginEvent` (async) |
| Sponge (API 8+) | `MinecraftAuth-Sponge` | `ServerSideConnectionEvent.Auth` |
| Velocity | `MinecraftAuth-Velocity` | `LoginEvent` + `ServerPreConnectEvent` (async) |
| BungeeCord | `MinecraftAuth-BungeeCord` | `LoginEvent` (async intent) + `ServerConnectEvent` |
| Fabric 1.16.5, 1.18.2, 1.19.x, 1.20.1, 1.21.1, 1.21.11, 26.1+ | `MinecraftAuth-Fabric-<mc>` (the `26.3` jar covers 26.1+) | mixin into `PlayerList.canPlayerLogin` |
| Forge 1.16.5, 1.18.2, 1.19.2, 1.20.1 | `MinecraftAuth-Forge-<mc>` | same mixin |
| NeoForge 1.21.1, 1.21.11, 26.1+ | `MinecraftAuth-Neoforge-<mc>` (the `26.3` jar covers 26.1+) | same mixin |

The Fabric jars need Fabric API. Everything else (Kotlin runtime included) is shaded and relocated
under `me.minecraftauth.libs`.

## Setup

1. Put the jar in `plugins/` (or `mods/`) and start the server once. That writes
   `MinecraftAuth.yml` (mods: `config/minecraftauth/MinecraftAuth.yml`).
2. Paste your server token from <https://minecraftauth.me/developers> into `api.server-token`.
3. Pick your `gatekeeper.conditions` and run `/minecraftauth reload` (alias `/mcauth`).

```yaml
gatekeeper:
  conditions:                       # any one of these lets a player in
    - TwitchSubscriber(2)
    - DiscordRole("123456789012345678") and not(DiscordRole("987654321098765432", "Banned"))
    - PatreonMember("Gold")
```

| Condition | Meaning |
|---|---|
| `TwitchFollower()` | follows your Twitch channel |
| `TwitchSubscriber([tier])` | subscribes on Twitch, optionally at tier 1-3 or higher |
| `YouTubeSubscriber()` / `YouTubeMember()` | subscribes to / is a member of your YouTube channel |
| `PatreonMember(["tier"])` | active patron, optionally of that tier |
| `DiscordServer("guild id")` | is in that Discord server (the MinecraftAuth bot must be in it too) |
| `DiscordRole("role id")` | has that Discord role |
| `DiscordRole("guild id", "role id or name")` | has that role in that Discord server; a role **name** always needs the guild ID |
| `Linked(["platform"])` | has linked their Minecraft account (and optionally that platform) |

Combine them with `and`, `or`, `not(...)`. Evaluation short-circuits, and answers are cached for
`api.cache-seconds`.

Proxies (Velocity, BungeeCord) can also gate individual backend servers under `gatekeeper.servers.<name>`;
this section only appears in, and is only read from, the proxy's config. Anything left
out there falls back to the top-level settings.

**Bypass:** list UUIDs or names under `gatekeeper.bypass`. With `bypass-admins: true`, operators
and players with the `minecraftauth.bypass` permission skip the checks too. Where that works on each
platform is in [Commands and permissions](#commands-and-permissions).

**When MinecraftAuth.me is unreachable**, `on-api-error: deny` (the default) keeps players out with
the `messages.error` text, and `allow` lets them in. A misconfigured token (HTTP 400/401/403)
always denies.

## Proxies and offline mode

On a Velocity or BungeeCord network, **install MinecraftAuth on the proxy, not on the backend
servers**, and keep the proxy in online mode (`online-mode = true` in `velocity.toml`,
`online_mode: true` in BungeeCord's `config.yml`).

Players link their real (Mojang) UUID. Backend servers behind a proxy run in offline mode, and
without player-info forwarding they see offline UUIDs, which have never been linked. A copy of
MinecraftAuth on such a backend denies everyone. The proxy sees the real UUID, so it can check
every login there, and per-server rules go under `gatekeeper.servers` in the proxy's config.

An offline-mode server or proxy can't use MinecraftAuth for the same reason: no player has a
linked UUID there.

## Bedrock players (Geyser / Floodgate)

Floodgate gives Bedrock players their own UUIDs (`00000000-0000-0000-...`), and MinecraftAuth.me
only links Java accounts, so a Bedrock player never meets a condition and is denied with
`messages.denied`. To let one in, either:

- link their Bedrock account to a Java account with Floodgate's account linking, so they join with
  the linked Java UUID, and have them link that Java account at minecraftauth.me, or
- add their Floodgate UUID or name to `gatekeeper.bypass`. Floodgate puts a prefix (`.` by default)
  in front of Bedrock names, so the name has to include it, e.g. `.SomePlayer`.

There is no setting that lets every Bedrock player in.

## Commands and permissions

| Command | Who can run it | What it does |
|---|---|---|
| `/minecraftauth` (alias `/mcauth`) | everyone | Shows the version and how many conditions are active, or that no server token is set |
| `/minecraftauth reload` | `minecraftauth.reload` | Re-reads `MinecraftAuth.yml` and clears the cached answers |

| Permission | Spigot / Paper / Folia | Velocity | BungeeCord | Sponge | Fabric / Forge / NeoForge |
|---|---|---|---|---|---|
| `minecraftauth.reload` | op by default | from your permissions plugin | from your permissions plugin or `config.yml` groups | from your permissions plugin | op level 3 (mods have no permission nodes) |
| `minecraftauth.bypass` | yes, and operators always bypass | yes, at login and when switching servers | at login: only `config.yml` groups with this permission or `*`; when switching servers: any permissions plugin | not supported, use `gatekeeper.bypass` | not supported, but operators (any level) bypass |

The console can always reload. `minecraftauth.bypass` and operator bypass only apply while
`bypass-admins` is `true`.

On Spigot/Paper/Folia the check still runs at pre-login for operators and permission holders,
because a player has no permissions until they exist. The answer is just ignored for them.
BungeeCord hasn't loaded permissions plugins for a player at login either, which is why only the
groups in its own `config.yml` count there.

## Troubleshooting

**Which message did the player get?** `messages.denied` means MinecraftAuth.me answered and none of
the conditions passed. `messages.error` means the check itself failed. The console says the same:
`Denying <name>: none of the conditions are met` or `... their accounts couldn't be checked`.

**Everyone gets kicked with `messages.error`:**

- The server token is wrong, or belongs to a different account. The console shows
  `MinecraftAuth.me rejected a check because of a configuration problem`. Copy the token again from
  <https://minecraftauth.me/developers> and run `/mcauth reload`.
- A `DiscordServer` or `DiscordRole` condition needs the MinecraftAuth bot in that Discord server,
  and the Discord account linked to your token must own the server or have Administrator or Manage
  Messages there. Without that you get the same configuration error.
- MinecraftAuth.me, Twitch, Discord, ... can't be reached and `on-api-error` is `deny`. The console
  shows `Couldn't check '<condition>' for <name>`. Set `on-api-error: allow` if you'd rather let
  players in during an outage.

**Everyone gets kicked with `messages.denied`:**

- The player hasn't linked their Minecraft account at <https://minecraftauth.me>, or hasn't linked
  the platform the condition needs (Twitch for `TwitchFollower()`, Discord for `DiscordRole(...)`, ...).
- The server or proxy sees offline UUIDs; see [Proxies and offline mode](#proxies-and-offline-mode).
- The token is from a different account than the channel, Patreon or Discord server you mean.
  Membership checks ask "does this player support the owner of this token?".

**Nobody is checked at all:** with no `api.server-token` the gatekeeper is off and the console says
so at startup. A condition that doesn't parse is skipped with `Ignoring invalid condition '...'`;
if every condition is invalid, the gatekeeper is off too.

**A change didn't take effect:** run `/mcauth reload`. Answers are otherwise cached for
`api.cache-seconds`, so a player who just followed or got a role may need to wait that long.

**`DiscordRole("123456789012345678")` or `DiscordRole("987654321098765432", "Subscriber")`?** A role
name always needs the server's guild ID, because anyone can add the MinecraftAuth bot to a server of
their own and create a role with the same name. `DiscordRole("Subscriber")` on its own is rejected
when the config loads. Prefer the role ID anyway: a name breaks when the role is renamed. To copy an
ID, turn on Developer Mode in Discord (Settings > Advanced), then right-click the role under Server
Settings > Roles and choose "Copy Role ID", or right-click the server icon and choose "Copy Server
ID".

## Migrating from v1

- **Replace the jar.** Remove the old one first. The new names are in the table at the top; mods
  are now one jar per Minecraft version and loader (`MinecraftAuth-Fabric-<mc>`,
  `MinecraftAuth-Forge-<mc>`, `MinecraftAuth-Neoforge-<mc>`).
- **Start from a fresh config.** Move the old `MinecraftAuth.yml` aside, start the server once so the
  v2 file is written, then copy your server token, conditions and bypass entries into it. Mods now
  keep their config in `config/minecraftauth/MinecraftAuth.yml`. If the token ends up missing, the
  gatekeeper stays off and says so in the console.
- **Conditions keep working.** `DiscordRole("guild id", "role")` works as before, and
  `DiscordRole("role id")` with just a role ID works too. A role *name* without its guild ID is
  rejected. `and`/`&&` and `or`/`||` both work.

## Mods: Stonecutter

`minecraft/mod` is one source tree compiled for every `<minecraft>-<loader>` target listed in
`settings.gradle.kts`. Each target's dependency versions live in
`minecraft/mod/versions/<target>/gradle.properties`. Differences between versions are marked with
Stonecutter comments (`//? if >=1.20 {`). Each loader's entrypoint is in its own package
(`me.minecraftauth.mod.fabric|forge|neoforge`), and only that package is compiled for the target.

```sh
./gradlew :minecraft:mod:buildAll                                  # every target
./gradlew :minecraft:mod:1.20.1-forge:build                        # one target
./gradlew ":minecraft:mod:Set active project to 26.3-fabric"       # switch the IDE / src view
./gradlew ":minecraft:mod:Reset active project"                    # back to the VCS version before committing
./gradlew :minecraft:mod:1.21.1-neoforge:runServer                 # dev server
./gradlew :minecraft:mod:1.20.1-forge:runtimeTest                  # runtime test of one target's jar
./gradlew :minecraft:mod:runtimeTestAll --continue                 # ... of every target, one server at a time
```

`runtimeTest` installs a real (production) Fabric/Forge/NeoForge server with the loader's own
installer into `versions/<target>/build/runtimeTest/server` (kept between runs). It puts the shipped
jar in `mods/` and checks the following:

- the server starts
- the default config is written
- `/minecraftauth` and `/mcauth reload` work
- an offline-mode test client on the bypass list gets in, and any other player is kicked through the
  `canPlayerLogin` mixin
- `stop` exits cleanly

MinecraftAuth.me is made unreachable for the test (HTTPS goes to a dead local proxy), so the kick
is the `on-api-error` message and no real API call is made. The server console ends up in
`server/console.log`. `-Pmcauth.runtimeTest.parallel=N` runs N servers at once.

To add a target, add it to the `versions(...)` list in `settings.gradle.kts` and create its
`versions/<target>/gradle.properties`.

**Known limitation:** vanilla runs `canPlayerLogin` on the server thread. A player's first check
can therefore hold up a tick until MinecraftAuth.me answers (at most the client timeout).
Repeat joins within `api.cache-seconds` are instant.
