package caddymcauth

import (
	"strconv"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig"
	"github.com/caddyserver/caddy/v2/caddyconfig/caddyfile"
	"github.com/caddyserver/caddy/v2/caddyconfig/httpcaddyfile"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
)

func init() {
	httpcaddyfile.RegisterGlobalOption("mcauth", parseGlobal)
	httpcaddyfile.RegisterHandlerDirective("mcauth", parseProtect)
	httpcaddyfile.RegisterHandlerDirective("mcauth_callback", parseCallback)
	// no `order` global option needed
	httpcaddyfile.RegisterDirectiveOrder("mcauth_callback", httpcaddyfile.Before, "basic_auth")
	httpcaddyfile.RegisterDirectiveOrder("mcauth", httpcaddyfile.After, "basic_auth")
}

// parseGlobal parses the mcauth global option:
//
//	mcauth {
//	    app_id         <id>
//	    app_secret     <secret>
//	    server_token   <token>                (deprecated: use app_secret)
//	    redirect       <url>
//	    session_key    <secret>
//	    session_ttl    <duration>
//	    membership_ttl <duration>
//	    on_api_error   deny|allow
//	    persist        true|false
//	    scopes         <platform...>
//	    cookie_prefix  <prefix>
//	    api_base       <url>
//	    luckperms      <rest api url> {
//	        api_key <key>
//	    }
//	    realm <name> {
//	        player <uuid|name...>
//	        linked
//	        discord_server <guild id>
//	        discord_role [guild id] <role id|name>   (a role name needs the guild id)
//	        twitch_follower
//	        twitch_subscriber [tier]
//	        patreon_member [tier title]
//	        youtube_subscriber
//	        youtube_member
//	        permission <permission> [<context>=<value>...]
//	        group <luckperms group> [<context>=<value>...]
//	        not <rule>                 # matches when <rule> doesn't
//	        all {                      # matches when every rule inside does
//	            <rule...>
//	        }
//	    }
//	}
func parseGlobal(d *caddyfile.Dispenser, _ any) (any, error) {
	app := &App{Realms: map[string]*Realm{}}
	d.Next() // option name
	if d.NextArg() {
		return nil, d.ArgErr()
	}
	for d.NextBlock(0) {
		key := d.Val()
		switch key {
		case "app_id", "app_secret", "server_token", "redirect", "session_key", "on_api_error", "cookie_prefix", "api_base":
			if !d.NextArg() {
				return nil, d.ArgErr()
			}
			v := d.Val()
			switch key {
			case "app_id":
				app.AppID = v
			case "app_secret":
				app.AppSecret = v
			case "server_token":
				app.ServerToken = v
			case "redirect":
				app.RedirectURL = v
			case "session_key":
				app.SessionKey = v
			case "on_api_error":
				app.OnAPIError = v
			case "cookie_prefix":
				app.CookiePrefix = v
			case "api_base":
				app.APIBase = v
			}
			if d.NextArg() {
				return nil, d.ArgErr()
			}
		case "session_ttl", "membership_ttl":
			if !d.NextArg() {
				return nil, d.ArgErr()
			}
			dur, err := caddy.ParseDuration(d.Val())
			if err != nil {
				return nil, d.Errf("invalid %s: %v", key, err)
			}
			if key == "session_ttl" {
				app.SessionTTL = caddy.Duration(dur)
			} else {
				app.MembershipTTL = caddy.Duration(dur)
			}
			if d.NextArg() {
				return nil, d.ArgErr()
			}
		case "persist":
			if !d.NextArg() {
				return nil, d.ArgErr()
			}
			b, err := strconv.ParseBool(d.Val())
			if err != nil {
				return nil, d.Errf("persist must be true or false")
			}
			app.Persist = &b
			if d.NextArg() {
				return nil, d.ArgErr()
			}
		case "luckperms":
			if app.LuckPerms != nil {
				return nil, d.Err("luckperms defined twice")
			}
			if !d.NextArg() {
				return nil, d.ArgErr()
			}
			lp := &LuckPerms{URL: d.Val()}
			if d.NextArg() {
				return nil, d.ArgErr()
			}
			for nesting := d.Nesting(); d.NextBlock(nesting); {
				switch d.Val() {
				case "api_key":
					if !d.NextArg() {
						return nil, d.ArgErr()
					}
					lp.APIKey = d.Val()
					if d.NextArg() {
						return nil, d.ArgErr()
					}
				default:
					return nil, d.Errf("unrecognized luckperms option %q", d.Val())
				}
			}
			app.LuckPerms = lp
		case "scopes":
			app.Scopes = append(app.Scopes, d.RemainingArgs()...)
		case "realm":
			if !d.NextArg() {
				return nil, d.ArgErr()
			}
			name := d.Val()
			if _, dup := app.Realms[name]; dup {
				return nil, d.Errf("realm %q defined twice", name)
			}
			rules, err := parseRules(d, name)
			if err != nil {
				return nil, err
			}
			app.Realms[name] = &Realm{Rules: rules}
		default:
			return nil, d.Errf("unrecognized mcauth option %q", key)
		}
	}
	return httpcaddyfile.App{Name: "mcauth", Value: caddyconfig.JSON(app, nil)}, nil
}

// parseRules parses the block of rules that follows a realm or an all rule. Each line is
// `[not] <rule> [<value>...]`, or `[not] all { <rules> }`.
func parseRules(d *caddyfile.Dispenser, realm string) ([]Rule, error) {
	var rules []Rule
	for nesting := d.Nesting(); d.NextBlock(nesting); {
		rule := Rule{Type: d.Val()}
		if rule.Type == "not" {
			if !d.NextArg() {
				return nil, d.Errf("not needs a rule to invert in realm %q", realm)
			}
			rule.Not, rule.Type = true, d.Val()
		}
		if _, ok := ruleArity[rule.Type]; !ok {
			return nil, d.Errf("unknown rule %q in realm %q", rule.Type, realm)
		}
		if rule.Type == "all" {
			if d.NextArg() {
				return nil, d.Errf("all takes a block of rules, not arguments, in realm %q", realm)
			}
			children, err := parseRules(d, realm)
			if err != nil {
				return nil, err
			}
			if len(children) == 0 {
				return nil, d.Errf("all needs a block of rules in realm %q", realm)
			}
			rule.All = children
		} else {
			rule.Values = d.RemainingArgs()
			if d.NextBlock(d.Nesting()) {
				return nil, d.Errf("only all takes a block, not %s, in realm %q", rule.Type, realm)
			}
		}
		rules = append(rules, rule)
	}
	return rules, nil
}

// parseProtect parses `mcauth [<matcher>] <realm>`.
func parseProtect(h httpcaddyfile.Helper) (caddyhttp.MiddlewareHandler, error) {
	h.Next()
	if !h.NextArg() {
		return nil, h.ArgErr()
	}
	p := &Protect{Realm: h.Val()}
	if h.NextArg() {
		return nil, h.ArgErr()
	}
	return p, nil
}

// parseCallback parses `mcauth_callback [<matcher>]`, typically `mcauth_callback /mcauth/callback`.
func parseCallback(h httpcaddyfile.Helper) (caddyhttp.MiddlewareHandler, error) {
	h.Next()
	if h.NextArg() {
		return nil, h.ArgErr()
	}
	return &Callback{}, nil
}
