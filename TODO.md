# TODO before launch

## Fix before launch

- **Caddy distribution.** Release `lib/go` first, then remove the `replace` line in `caddy/go.mod` and release
      `caddy` (RELEASING.md). Register the module on caddyserver.com/download so people can find it without xcaddy.
