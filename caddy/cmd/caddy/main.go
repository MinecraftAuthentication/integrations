// Command caddy is Caddy with the mcauth module built in, for local development:
//
//	go run ./cmd/caddy run --config examples/Caddyfile
//
// For production builds use xcaddy (see README.md).
package main

import (
	caddycmd "github.com/caddyserver/caddy/v2/cmd"

	_ "github.com/caddyserver/caddy/v2/modules/standard"

	_ "github.com/MinecraftAuthentication/integrations/caddy/v2"
)

func main() {
	caddycmd.Main()
}
