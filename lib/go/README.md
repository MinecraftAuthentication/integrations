# mcauth (Go)

Go client for the [MinecraftAuth.me v2 API](https://minecraftauth.me/developers/docs/api-reference). Its only
dependency is `github.com/golang-jwt/jwt/v5`, for grant tokens. The [Caddy module](../../caddy) is built on it.

```sh
go get github.com/MinecraftAuthentication/integrations/lib/go
```

```go
import mcauth "github.com/MinecraftAuthentication/integrations/lib/go"
```

## Membership checks

```go
client := &mcauth.Client{
	UserAgent:   "MyBot/1.2", // required by the API: name your integration and its version
	Credentials: mcauth.ServerToken(os.Getenv("MCAUTH_TOKEN")), // from https://minecraftauth.me/developers
}

ctx := context.Background()
uuid := "069a79f4-44e9-4726-a5be-fca90e38aaf5"

ok, err := client.Following(ctx, uuid, mcauth.Twitch)                 // follows your Twitch channel
ok, err = client.Following(ctx, uuid, mcauth.Google)                  // subscribed to your YouTube channel
ok, err = client.Subscribed(ctx, uuid, mcauth.Twitch, "2")            // Twitch sub, tier 2 or higher
ok, err = client.Subscribed(ctx, uuid, mcauth.Patreon, "Gold")        // Patreon tier title
ok, err = client.DiscordRole(ctx, uuid, "123456789012345678", "")     // has a Discord role, by ID
ok, err = client.DiscordRole(ctx, uuid, "Subscriber", "987654321098765432") // or by name, in that server
ok, err = client.DiscordPresent(ctx, uuid, "987654321098765432")      // in a Discord server

switch {
case err == nil:
	// ok is the answer
case mcauth.IsConfig(err):
	// 400, 401, 403: check the token, role or server ID. Retrying won't help.
case mcauth.IsTemporary(err):
	// network error, 409, 429, 5xx: try again later
}
```

Checks return `false, nil` when the player isn't linked or hides the account. Other failures are `*mcauth.APIError`
(`Status` is 0 when no response arrived); `IsConfig`, `IsTemporary` and `IsNotFound` classify them. Every method
honours the context's deadline and cancellation.

## Lookups

```go
result, err := client.Lookup(ctx, mcauth.Discord, "123456789012345678", mcauth.Minecraft)
if err == nil && result != nil {
	if acc, ok := result.Accounts[mcauth.Minecraft]; ok && acc.Identifier != nil {
		fmt.Println("Minecraft UUID:", *acc.Identifier)
	}
}
```

`Lookup` returns `nil, nil` when there is no such identity, or it's hidden from you. The trailing platforms limit
the result (none = all). An account with `Hint: true` is linked, but the player hides which one. Lookups work
without credentials.

## Access (grant) requests

These let a player sign in with MinecraftAuth.me. They need application credentials, so keep the secret on your
server.

```go
client := &mcauth.Client{
	UserAgent:   "MySite/1.0",
	Credentials: mcauth.ApplicationCredentials{ID: appID, Secret: appSecret},
}

req, err := client.CreateGrantRequest(ctx, []mcauth.Platform{mcauth.Discord}, true)
fmt.Printf("Open %s, or enter %s at %s\n", req.URL, req.Code, req.VerificationURL)

// Poll every req.Interval seconds until the status is no longer pending.
status, err := client.GetGrantRequest(ctx, req.ID)
if err == nil && status.Status == mcauth.GrantAccepted {
	keys := &mcauth.KeySource{Client: client} // fetches and caches the signing key; create it once
	claims, err := mcauth.VerifyGrantToken(ctx, keys, status.Token, mcauth.VerifyOptions{Audience: appID})
	if err == nil {
		mc, _ := claims.Minecraft()
		fmt.Println(claims.Subject, mc.Identifier, claims.Accounts[mcauth.Discord].Name)
	}
}
```

The Go client has no blocking `awaitGrant` helper: poll `GetGrantRequest` yourself at `req.Interval` seconds, until
`req.ExpiresAt` (epoch milliseconds) at the latest.

Tokens that reach you through a browser redirect **must** be verified, with your application ID as `Audience` so a
token issued to another application is rejected. `VerifyGrantToken` checks the RS512 signature, issuer,
`iat`/`exp`/`nbf` (with `Leeway`, 1 minute by default; `exp` is required) and the audience. `MaxAge` can shorten the
token's lifetime. Failures wrap `mcauth.ErrInvalidToken`. When the signature doesn't match, `KeySource` refetches the
key (at most once per `RefreshInterval`, 1 minute by default) in case it rotated.

## Client fields

| Field | Default | |
|---|---|---|
| `UserAgent` | (required) | Sent as `User-Agent` |
| `Credentials` | `nil` (anonymous) | `mcauth.ServerToken(token)` or `mcauth.ApplicationCredentials{ID, Secret}` |
| `BaseURL` | `https://minecraftauth.me` | |
| `HTTPClient` | a client with a 10 second timeout | |

A `*Client` is safe for concurrent use.

## Development

```sh
go test ./...                    # from lib/go
./gradlew :lib:go:build          # from the repository root: build + vet + test
```

Releasing is covered in [RELEASING.md](../../RELEASING.md).
