# Caddyfile examples

Each file is a complete, working Caddyfile for one use case. Pick the closest one, replace
`example.com` and the IDs, and set the environment variables it uses.

| File | Use case |
|---|---|
| [`basic.Caddyfile`](basic.Caddyfile) | Smallest setup: any logged-in MinecraftAuth.me player |
| [`players.Caddyfile`](players.Caddyfile) | Allowlist of specific players by UUID or name |
| [`twitch.Caddyfile`](twitch.Caddyfile) | Twitch followers, subscribers and subscriber tiers |
| [`discord.Caddyfile`](discord.Caddyfile) | Discord server members and roles; moderators-only editing |
| [`patreon.Caddyfile`](patreon.Caddyfile) | Patreon patrons and specific tiers |
| [`youtube.Caddyfile`](youtube.Caddyfile) | YouTube subscribers and channel members |
| [`luckperms.Caddyfile`](luckperms.Caddyfile) | LuckPerms permissions, groups and contexts |
| [`combining.Caddyfile`](combining.Caddyfile) | `any` / `all` / `not`, and several `mcauth` directives on one route |
| [`routes.Caddyfile`](routes.Caddyfile) | Different realms per path, public paths, APIs |
| [`backend-headers.Caddyfile`](backend-headers.Caddyfile) | Passing the player's UUID and name to a backend and the access log |
| [`error-pages.Caddyfile`](error-pages.Caddyfile) | Custom 400 / 401 / 403 / 503 pages |
| [`options.Caddyfile`](options.Caddyfile) | Every `mcauth` option with its default |
| [`Caddyfile`](Caddyfile) | A bit of everything |

## Environment variables

| Variable | Used for |
|---|---|
| `MCAUTH_APP_ID` | your application's ID from <https://minecraftauth.me/developers> |
| `MCAUTH_SESSION_KEY` | 32+ random characters (`openssl rand -hex 32`) |
| `MCAUTH_SERVER_TOKEN` | your server token; only for Twitch, Discord, Patreon and YouTube rules |
| `LUCKPERMS_REST_KEY` | a key from `LUCKPERMS_REST_AUTH_KEYS`; only for `permission` and `group` rules |

## Running one

```sh
caddy run --config examples/twitch.Caddyfile
caddy adapt --config examples/twitch.Caddyfile --validate   # check it without starting
```

Files ending in `.Caddyfile` are read as Caddyfiles automatically.

Logins only work on the host of the `redirect` URL, because the session cookie belongs to that
host. Protect paths on one site rather than spreading the login across several hosts.
