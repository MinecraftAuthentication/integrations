# @minecraftauth/lib

JavaScript/TypeScript client for the [MinecraftAuth.me v2 API](https://minecraftauth.me/developers/docs/api-reference).
It has no dependencies and only uses `fetch` and WebCrypto, so it runs on Node.js 22+, Deno, Bun, Cloudflare
Workers and in browsers. It ships as ESM with type definitions.

```sh
npm install @minecraftauth/lib
```

## Membership checks

```ts
import { MinecraftAuth, application, isConfig, isTemporary } from "@minecraftauth/lib";

const mcauth = new MinecraftAuth({
  userAgent: "MyBot/1.2", // required by the API: name your integration and its version
  // an application from https://minecraftauth.me/developers: checks run as you, its owner
  credentials: application(process.env.MCAUTH_APP_ID!, process.env.MCAUTH_APP_SECRET!),
});

const uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
try {
  await mcauth.following(uuid, "twitch"); // follows your Twitch channel
  await mcauth.following(uuid, "google"); // subscribed to your YouTube channel
  await mcauth.subscribed(uuid, "twitch", "2"); // Twitch sub, tier 2 or higher
  await mcauth.subscribed(uuid, "patreon", "Gold"); // Patreon tier title
  await mcauth.discordRole(uuid, "123456789012345678"); // has a Discord role, by ID
  await mcauth.discordRole(uuid, "Subscriber", "987654321098765432"); // or by name, in that server
  await mcauth.discordPresent(uuid, "987654321098765432"); // in a Discord server
} catch (err) {
  if (isConfig(err)) console.error("check the credentials / role / server id", err); // 400, 401, 403
  else if (isTemporary(err)) console.warn("try again later", err); // network, 409, 429, 5xx
  else throw err;
}
```

Checks resolve to `false` when the player isn't linked or hides the account. Other failures reject with a
`MinecraftAuthError`, which has a `status` (0 when no response arrived).

**Rate limits.** When the API rate limits the client (429 with `Retry-After`), a call waits and retries on its own, for
up to `maxRateLimitWaitMs` in all (10 000 by default; 0 never waits). If it would have to wait longer, it rejects with
a `RateLimitError` (check with `isRateLimited`), whose `retryAfterMs` says how long to wait. Until that time has
passed, later calls reject with it right away without being sent.

## Lookups

```ts
const result = await mcauth.lookup("discord", "123456789012345678", { include: ["minecraft"] });
const minecraft = result?.accounts.minecraft?.identifier; // undefined if hidden or not linked
```

`lookup` resolves to `null` when there is no such identity, or it's hidden from you. Lookups work without
credentials.

## Access (grant) requests

Use these to let a player sign in with MinecraftAuth.me. They need application credentials, so run this
server-side and never ship the secret to a browser.

```ts
import { MinecraftAuth, KeySource, application, verifyGrantToken } from "@minecraftauth/lib";

const mcauth = new MinecraftAuth({ userAgent: "MySite/1.0", credentials: application(APP_ID, APP_SECRET) });

const request = await mcauth.createGrantRequest({ scopes: ["discord"] });
console.log(`Open ${request.url}, or enter ${request.code} at ${request.verificationUrl}`);

const status = await mcauth.awaitGrant(request); // polls until accepted/denied/expired; accepts { signal }
if (status.status === "accepted") {
  const keys = new KeySource(mcauth); // fetches and caches the signing key; create it once
  const claims = await verifyGrantToken(keys, status.token!, { audience: APP_ID });
  console.log(claims.subject, claims.accounts.minecraft?.identifier, claims.accounts.discord?.name);
}
```

Tokens that reach you through a browser redirect **must** be verified, with your application ID as `audience` so a
token issued to another application is rejected. Tokens expire 10 minutes after the player approves; `maxAgeMs`
can shorten that:

```ts
const claims = await verifyGrantToken(keys, tokenFromQuery, { audience: APP_ID, maxAgeMs: 5 * 60_000 });
```

`verifyGrantToken` checks the RS512 signature, issuer, `iat`/`exp`/`nbf` (with 60 seconds of leeway; `exp` is
required) and the audience. It rejects with an `InvalidTokenError`. If the signing key rotated, the key is fetched again, at most
once per minute.

## Options

| Option | Default | |
|---|---|---|
| `userAgent` | (required) | Sent as `User-Agent`. Browsers may ignore it. |
| `credentials` | `anonymous` | `application(id, secret)`, or the deprecated `serverToken(token)` / `serverTokenBasic(uid, token)` |
| `baseUrl` | `https://minecraftauth.me` | |
| `timeoutMs` | `10000` | Per request, `0` disables it |
| `fetch` | `globalThis.fetch` | Inject a custom fetch (proxies, tests) |

Every method also takes `{ signal }`. An aborted call rejects with the signal's reason.

## Development

```sh
npm ci
npm run typecheck && npm test && npm run build
```

Or run `./gradlew :lib:js:build` from the repository root.
