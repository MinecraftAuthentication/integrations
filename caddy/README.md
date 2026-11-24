# MinecraftAuth for Caddy

A [Caddy](https://caddyserver.com) module that puts sites and routes behind a
[MinecraftAuth.me](https://minecraftauth.me) login. You decide which Minecraft players get through:
specific players, anyone who follows, subscribes to or supports you on Twitch, Discord, Patreon
or YouTube, or anyone with a [LuckPerms](https://luckperms.net) permission.

## Install

```sh
xcaddy build --with github.com/MinecraftAuthentication/integrations/caddy
```

or with Docker:

```dockerfile
FROM caddy:2-builder AS builder
RUN xcaddy build --with github.com/MinecraftAuthentication/integrations/caddy

FROM caddy:2
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
```

## Set up

1. At <https://minecraftauth.me/developers>, create an **application** (it needs a linked Discord
   account). Add your callback URL, e.g. `https://example.com/mcauth/callback`, to its redirect URLs.
2. For membership rules, also copy your **server token** from the same page.
3. Configure Caddy:

```caddyfile
{
	mcauth {
		app_id       {env.MCAUTH_APP_ID}
		redirect     https://example.com/mcauth/callback
		server_token {env.MCAUTH_SERVER_TOKEN}   # only for membership rules
		session_key  {env.MCAUTH_SESSION_KEY}    # 32+ random characters

		realm staff {
			player 069a79f4-44e9-4726-a5be-fca90e38aaf5 Notch
		}
		realm supporters {
			twitch_subscriber 2
			discord_role 123456789012345678
			patreon_member "Gold"
		}
	}
}

example.com {
	mcauth_callback /mcauth/callback

	route /admin/* {
		mcauth staff
		reverse_proxy localhost:9000 {
			header_up X-Minecraft-Name {mcauth.name}
		}
	}

	mcauth supporters
	file_server
}
```

You don't need an `order` option: the `mcauth_callback` and `mcauth` directives are ordered around
`basic_auth`.

## Examples

The [`examples/`](examples/) directory has a complete Caddyfile for each use case
([index](examples/README.md)):

| Example | Use case |
|---|---|
| [`basic`](examples/basic.Caddyfile) | Any logged-in MinecraftAuth.me player |
| [`players`](examples/players.Caddyfile) | Allowlist of specific players |
| [`twitch`](examples/twitch.Caddyfile) | Twitch followers, subscribers and tiers |
| [`discord`](examples/discord.Caddyfile) | Discord server members and roles |
| [`patreon`](examples/patreon.Caddyfile) | Patreon patrons and tiers |
| [`youtube`](examples/youtube.Caddyfile) | YouTube subscribers and members |
| [`luckperms`](examples/luckperms.Caddyfile) | LuckPerms permissions, groups and contexts |
| [`combining`](examples/combining.Caddyfile) | `all`, `not` and several `mcauth` directives |
| [`routes`](examples/routes.Caddyfile) | Different realms per path, public paths, APIs |
| [`backend-headers`](examples/backend-headers.Caddyfile) | Passing the player to a backend |
| [`error-pages`](examples/error-pages.Caddyfile) | Custom 400 / 401 / 403 / 503 pages |
| [`options`](examples/options.Caddyfile) | Every `mcauth` option |

## Reference

### `mcauth` global option

| Option | Default | |
|---|---|---|
| `app_id` | required | your MinecraftAuth.me application ID |
| `redirect` | required | absolute URL of your `mcauth_callback` route, registered on the application |
| `server_token` | none | your server token; required by membership rules |
| `session_key` | random per start | encrypts cookies (AES-256-GCM). Set it, or every restart logs everyone out |
| `session_ttl` | `24h` | how long a login lasts |
| `membership_ttl` | `5m` | how long membership answers are cached |
| `on_api_error` | `deny` | `deny` (respond 503) or `allow` when MinecraftAuth.me or a platform can't answer |
| `persist` | `true` | remember the player's approval; `false` asks them every login |
| `scopes` | none | extra platforms to request at login (`minecraft` is always requested) |
| `cookie_prefix` | `mcauth` | cookies are `<prefix>_session` and `<prefix>_state` |
| `luckperms <url> { api_key <key> }` | none | your [LuckPerms REST API](#luckperms); required by `permission` and `group` rules |
| `realm <name> { ... }` | | a set of rules; a player gets in when **any** rule matches (see [Combining rules](#combining-rules)) |

### Realm rules

| Rule | Lets in players who... | Needs `server_token` |
|---|---|---|
| `player <uuid\|name>...` | are one of these Minecraft players | no |
| `linked` | logged in with any MinecraftAuth.me account | no |
| `discord_server <guild id>` | are in that Discord server (the MinecraftAuth bot must be too) | yes |
| `discord_role <role id>` or `discord_role <guild id> <role id\|name>` | have that Discord role (a role name needs its guild ID; you must moderate that server) | yes |
| `twitch_follower` | follow your Twitch channel | yes |
| `twitch_subscriber [tier]` | subscribe on Twitch (at tier 1-3 or higher) | yes |
| `patreon_member [tier title]` | are active patrons (of that tier) | yes |
| `youtube_subscriber` | subscribe to your YouTube channel | yes |
| `youtube_member` | are members of your YouTube channel | yes |
| `permission <permission> [<context>=<value>...]` | have that LuckPerms permission (in those contexts) | no, needs `luckperms` |
| `group <group> [<context>=<value>...]` | are in that LuckPerms group, directly or inherited (shorthand for `permission group.<group>`) | no, needs `luckperms` |

"Your" means the owner of `server_token`.

### Combining rules

A realm lets a player in when **any** of its rules matches. To combine rules differently:

- `not <rule>` matches when the rule doesn't, e.g. `not group shunned` or `not player Notch`.
- `all { ... }` matches only when **every** rule inside it matches. It can hold `not` rules and
  other `all` blocks, and `not all { ... }` matches unless all of them do.

```caddyfile
realm vip {
	player Notch                  # always let in
	all {                         # or: a tier 2+ subscriber who isn't shunned
		twitch_subscriber 2
		not group shunned
	}
}
```

- If a check can't be answered because the API is down, `not` doesn't count that as a non-match:
  the result is still "couldn't check", so an outage never lets anyone through a `not` rule.
- An `all` stops at the first rule that doesn't match, and checks `player` and `linked` rules before
  calling any API.
- A realm whose only rule is `not ...` lets in **every** logged-in player that rule doesn't match.
- Another way to require several realms is to put several `mcauth` directives on one route.

### LuckPerms

`permission` and `group` rules ask a [LuckPerms REST API](https://luckperms.net/wiki/Standalone-and-REST-API)
(the standalone LuckPerms app with the REST extension) connected to the same database as your
servers, so you can gate routes with the permissions and groups you already manage in game:

```caddyfile
mcauth {
	# ...
	luckperms http://localhost:8080 {
		api_key {env.LUCKPERMS_REST_KEY}   # one of LUCKPERMS_REST_AUTH_KEYS; omit if auth is off
	}

	realm staff {
		permission website.admin                 # global permission
		permission website.mod server=survival   # only counts in the survival server context
		group admin                              # same as: permission group.admin
	}
}
```

- Contexts (`server=survival`, `world=nether`) are sent as the check's query contexts. Keys and
  values are lowercase letters and digits.
- Answers are cached for `membership_ttl`, like membership rules. A REST API that's down or rejects
  the key counts as an API error (see `on_api_error`).
- Run the REST API on a private network or behind HTTPS; anyone who can reach it with a key can
  edit your permissions.

### Directives

- `mcauth_callback [<matcher>]` serves the redirect URL. It checks the `state`, verifies the signed
  grant token and starts the session.
- `mcauth [<matcher>] <realm>` lets matching players through. (This is the site directive; the `mcauth { }` block above is the global option.)
  - Browsers without a session are redirected to MinecraftAuth.me; other methods get **401**.
  - Players who don't match the realm get **403**, and **503** when the API is down and
    `on_api_error deny` is set. Customize these pages with `handle_errors`.

Allowed requests get the placeholders `{mcauth.uuid}` and `{mcauth.name}`.

## Security notes

- The grant token comes back in the callback's query string. Keep it out of access logs:

  ```caddyfile
  log {
  	format filter {
  		request>uri query {
  			delete token
  		}
  	}
  }
  ```
- Grant tokens currently have no `aud` or `exp` claim. A token another MinecraftAuth.me
  application received could be replayed here within the 10-minute window. The module already
  checks `aud` and `exp` whenever a token carries them.
- Membership and LuckPerms permissions are rechecked (subject to `membership_ttl`) on every request, so access goes away when
  a player stops supporting you. Removing them from a `player` rule takes effect immediately.

## Development

```sh
go test ./...
go run ./cmd/caddy run --config examples/Caddyfile
```
