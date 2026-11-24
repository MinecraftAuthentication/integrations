# me.minecraftauth:lib

Kotlin/Java client for the [MinecraftAuth.me v2 API](https://minecraftauth.me/developers/docs/api-reference).
It targets Java 8, and its only dependency is `kotlinx-serialization-json`, which brings the Kotlin standard library
with it. Every Minecraft integration in this repository uses it.

## Installation

The library is published to `https://nexus.scarsz.me/content/repositories/releases/`.

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    maven("https://nexus.scarsz.me/content/repositories/releases/")
}

dependencies {
    implementation("me.minecraftauth:lib:2.0.0")
}
```

**Maven**

```xml
<repositories>
    <repository>
        <id>scarsz</id>
        <url>https://nexus.scarsz.me/content/repositories/releases/</url>
    </repository>
</repositories>

<dependency>
    <groupId>me.minecraftauth</groupId>
    <artifactId>lib</artifactId>
    <version>2.0.0</version>
</dependency>
```

If you ship it inside a Minecraft plugin, shade **and relocate** it (and `kotlin` / `kotlinx`), so it doesn't clash
with other plugins that bundle a different version.

## Membership checks

**Kotlin**

```kotlin
import me.minecraftauth.lib.*
import me.minecraftauth.lib.grant.*
import java.util.UUID

val mcauth = MinecraftAuth(
    MinecraftAuthConfig(
        userAgent = "MyPlugin/1.2", // required by the API: name your integration and its version
        credentials = Credentials.ServerToken(System.getenv("MCAUTH_TOKEN")), // from https://minecraftauth.me/developers
    )
)

val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
try {
    mcauth.following(uuid, Platform.TWITCH)                                  // follows your Twitch channel
    mcauth.following(uuid, Platform.GOOGLE)                                  // subscribed to your YouTube channel
    mcauth.subscribed(uuid, Platform.TWITCH, "2")                            // Twitch sub, tier 2 or higher
    mcauth.subscribed(uuid, Platform.PATREON, "Gold")                        // Patreon tier title
    mcauth.discordRole(uuid, "123456789012345678")                           // has a Discord role, by ID
    mcauth.discordRole(uuid, "Subscriber", serverId = "987654321098765432")  // or by name, in that server
    mcauth.discordPresent(uuid, "987654321098765432")                        // in a Discord server
} catch (e: ConfigurationException) {
    // 400, 401, 403: check the token, role or server ID. Retrying won't help.
} catch (e: UnavailableException) {
    // network error, 409, 429, 5xx: try again later
}
```

**Java**

```java
import me.minecraftauth.lib.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

MinecraftAuth mcauth = new MinecraftAuth(
        new MinecraftAuthConfig("MyPlugin/1.2", new Credentials.ServerToken(System.getenv("MCAUTH_TOKEN"))));

UUID uuid = player.getUniqueId();
CompletableFuture<Boolean> sub = mcauth.subscribedAsync(uuid, Platform.TWITCH, "2");
sub.whenComplete((subscribed, error) -> {
    if (error != null) { /* unwrap error.getCause(): ConfigurationException or UnavailableException */ return; }
    // back on your own thread before touching the game
});
```

Checks return `false` when the player isn't linked or hides the account. Every other failure throws a subclass of
`MinecraftAuthException`, which carries the HTTP `status` (`null` when no response arrived).

**Every call blocks on network I/O.** Don't call one on a game's main thread. Use the `…Async` variants, which return
a `CompletableFuture` and run on a small shared daemon pool, or pass your own `Executor` to the constructor:
`MinecraftAuth(config, executor)`.

## Lookups

```kotlin
val result = mcauth.lookup(Platform.DISCORD, "123456789012345678", include = setOf(Platform.MINECRAFT))
val minecraft: UUID? = result?.minecraft // null if hidden or not linked
```

`lookup` returns `null` when there is no such identity, or it's hidden from you. A platform missing from
`result.accounts` means unlinked, hidden or not requested, never "definitely has no account". Lookups work without
credentials (`Credentials.None`).

## Access (grant) requests

These let a player sign in with MinecraftAuth.me. They need application credentials, so keep the secret on your
server and never ship it inside something you hand to players.

```kotlin
val mcauth = MinecraftAuth(MinecraftAuthConfig("MySite/1.0", Credentials.Application(APP_ID, APP_SECRET)))

val request = mcauth.createGrantRequest(scopes = listOf(Platform.DISCORD))
println("Open ${request.url}, or enter ${request.code} at ${request.verificationUrl}")

val status = mcauth.awaitGrant(request) // blocks, polling until accepted/denied/expired; or awaitGrantAsync
if (status.status == GrantStatus.ACCEPTED) {
    val keys = KeySource(mcauth) // fetches and caches the signing key; create it once
    val verifier = GrantTokenVerifier(keys, audience = APP_ID)
    val claims = verifier.verify(status.token!!)
    println("${claims.subject} ${claims.minecraftUuid} ${claims.accounts[Platform.DISCORD]?.name}")
}
```

Tokens that reach you through a browser redirect **must** be verified, with your application ID as `audience` so a
token issued to another application is rejected. `GrantTokenVerifier` checks the RS512 signature, issuer, `iat` and
`exp` (60 seconds of clock skew; `exp` is required) and the audience; `maxAgeSeconds` can shorten the token's
lifetime. Failures throw `InvalidTokenException`.

If the signing key rotated, `KeySource` fetches it again when a signature doesn't match, at most once per minute.
A failure to fetch the key throws `UnavailableException`, not `InvalidTokenException`.

## Options

`MinecraftAuthConfig` takes:

| Option | Default | |
|---|---|---|
| `userAgent` | (required) | Sent as `User-Agent`; a blank one is rejected |
| `credentials` | `Credentials.None` | `ServerToken(token)`, `Application(id, secret)`, or the legacy `ServerTokenBasic(uid, token)` |
| `baseUrl` | `https://minecraftauth.me` | |
| `connectTimeoutMillis` | `5000` | |
| `readTimeoutMillis` | `10000` | |

## Development

```sh
./gradlew :lib:jvm:build                  # compile + test
./gradlew :lib:jvm:publishToMavenLocal    # install into ~/.m2 to try it in another project
```

Releasing is covered in [RELEASING.md](../../RELEASING.md).
