// Package caddymcauth is a Caddy module that puts sites and routes behind a MinecraftAuth.me login, letting
// through only the Minecraft players you choose: by UUID or name, or by what they've linked on
// MinecraftAuth.me (Twitch follower or subscriber, Discord server member or role, Patreon patron, YouTube
// subscriber or member).
package caddymcauth

import (
	"crypto/rand"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/caddyserver/caddy/v2"
	"go.uber.org/zap"

	mcauth "github.com/MinecraftAuthentication/integrations/lib/go"
)

func init() {
	caddy.RegisterModule(&App{})
}

// Version of this module, sent in the User-Agent.
const Version = "0.1.0"

// App is the `mcauth` app: the MinecraftAuth.me configuration and realms shared by the
// `mcauth` and `mcauth_callback` handlers.
type App struct {
	// AppID is your application's ID from https://minecraftauth.me/developers. Its registered redirect URLs
	// must include RedirectURL.
	AppID string `json:"app_id"`
	// ServerToken is your server token from https://minecraftauth.me/developers. Only needed for membership
	// rules (twitch_follower, discord_role, ...), which answer "does this player support me?".
	ServerToken string `json:"server_token,omitempty"`
	// RedirectURL is where MinecraftAuth.me sends players back to: the absolute URL of a `mcauth_callback` route.
	RedirectURL string `json:"redirect"`
	// SessionKey encrypts the session cookies. If empty, a random key is generated on every start, which
	// logs everyone out when Caddy restarts.
	SessionKey string `json:"session_key,omitempty"`
	// SessionTTL is how long a login lasts (default 24h).
	SessionTTL caddy.Duration `json:"session_ttl,omitempty"`
	// MembershipTTL is how long a membership answer is cached (default 5m).
	MembershipTTL caddy.Duration `json:"membership_ttl,omitempty"`
	// OnAPIError is "deny" (default) or "allow": what to do when MinecraftAuth.me or an upstream platform
	// can't answer a membership check.
	OnAPIError string `json:"on_api_error,omitempty"`
	// Persist stores the player's approval so they aren't asked again (default true).
	Persist *bool `json:"persist,omitempty"`
	// Scopes are extra platforms to request in the login (minecraft is always included).
	Scopes []string `json:"scopes,omitempty"`
	// CookiePrefix names the cookies (default "mcauth").
	CookiePrefix string `json:"cookie_prefix,omitempty"`
	// APIBase overrides https://minecraftauth.me (testing).
	APIBase string `json:"api_base,omitempty"`
	// LuckPerms is the LuckPerms REST API that `permission` and `group` rules ask. Only needed for those rules.
	LuckPerms *LuckPerms `json:"luckperms,omitempty"`
	// Realms are named sets of rules; a player is let into a realm when any of its rules match.
	Realms map[string]*Realm `json:"realms,omitempty"`

	client  *mcauth.Client
	lp      *luckPermsClient
	keys    *mcauth.KeySource
	cookies *cookieCodec
	cache   *membershipCache
	logger  *zap.Logger
}

// Realm is a named set of rules. A player is allowed when any rule matches; use an `all` rule to require
// several at once and Not to invert one.
type Realm struct {
	Rules []Rule `json:"rules"`
}

// Rule is one way into a realm.
//
// Types: player (Values: UUIDs or names), linked, discord_server (guild ID), discord_role (role ID, or guild ID and role ID or name),
// twitch_follower, twitch_subscriber (optional minimum tier), patreon_member (optional tier title),
// youtube_subscriber, youtube_member, permission (LuckPerms permission, then optional key=value contexts),
// group (LuckPerms group, shorthand for permission group.<name>, then optional contexts),
// all (matches when every rule in All matches).
type Rule struct {
	Type   string   `json:"type"`
	Values []string `json:"values,omitempty"`
	// Not inverts the rule: it matches when the rule doesn't. A check that fails (API down) stays failed.
	Not bool `json:"not,omitempty"`
	// All holds the rules of an `all` rule.
	All []Rule `json:"all,omitempty"`
}

// CaddyModule returns the Caddy module information.
func (*App) CaddyModule() caddy.ModuleInfo {
	return caddy.ModuleInfo{
		ID:  "mcauth",
		New: func() caddy.Module { return new(App) },
	}
}

var ruleArity = map[string][2]int{ // min, max values (-1 = unlimited)
	"player":             {1, -1},
	"linked":             {0, 0},
	"discord_server":     {1, 1},
	"discord_role":       {1, 2},
	"twitch_follower":    {0, 0},
	"twitch_subscriber":  {0, 1},
	"patreon_member":     {0, 1},
	"youtube_subscriber": {0, 0},
	"youtube_member":     {0, 0},
	"permission":         {1, -1},
	"group":              {1, -1},
	"all":                {0, 0},
}

// needsAPI reports a rule that calls out to MinecraftAuth.me or LuckPerms; player and linked are decided
// locally, and an all rule needs the API when any rule inside it does.
func (r Rule) needsAPI() bool {
	switch r.Type {
	case "player", "linked":
		return false
	case "all":
		for _, child := range r.All {
			if child.needsAPI() {
				return true
			}
		}
		return false
	}
	return true
}

// usesLuckPerms reports a rule answered by the LuckPerms REST API.
func (r Rule) usesLuckPerms() bool { return r.Type == "permission" || r.Type == "group" }

// needsServerToken reports a MinecraftAuth.me membership rule (not counting the rules inside an all).
func (r Rule) needsServerToken() bool { return r.needsAPI() && !r.usesLuckPerms() && r.Type != "all" }

// Provision sets up the app.
func (a *App) Provision(ctx caddy.Context) error {
	a.logger = ctx.Logger()
	repl := caddy.NewReplacer()
	a.AppID = repl.ReplaceKnown(a.AppID, "")
	a.ServerToken = repl.ReplaceKnown(a.ServerToken, "")
	a.RedirectURL = repl.ReplaceKnown(a.RedirectURL, "")
	a.SessionKey = repl.ReplaceKnown(a.SessionKey, "")
	if a.LuckPerms != nil {
		a.LuckPerms.URL = repl.ReplaceKnown(a.LuckPerms.URL, "")
		a.LuckPerms.APIKey = repl.ReplaceKnown(a.LuckPerms.APIKey, "")
		a.lp = newLuckPermsClient(a.LuckPerms)
	}

	if a.SessionTTL == 0 {
		a.SessionTTL = caddy.Duration(24 * time.Hour)
	}
	if a.MembershipTTL == 0 {
		a.MembershipTTL = caddy.Duration(5 * time.Minute)
	}
	if a.OnAPIError == "" {
		a.OnAPIError = "deny"
	}
	if a.CookiePrefix == "" {
		a.CookiePrefix = "mcauth"
	}

	a.client = &mcauth.Client{
		BaseURL:   a.APIBase,
		UserAgent: "MinecraftAuth-Caddy/" + Version,
	}
	if a.ServerToken != "" {
		a.client.Credentials = mcauth.ServerToken(a.ServerToken)
	}
	a.keys = &mcauth.KeySource{Client: &mcauth.Client{BaseURL: a.APIBase, UserAgent: a.client.UserAgent}}
	a.cache = newMembershipCache(time.Duration(a.MembershipTTL))

	key := []byte(a.SessionKey)
	if len(key) == 0 {
		key = make([]byte, 32)
		if _, err := rand.Read(key); err != nil {
			return err
		}
		a.logger.Warn("mcauth: no session_key set; generated a random one, so logins won't survive a restart")
	}
	codec, err := newCookieCodec(key)
	if err != nil {
		return err
	}
	a.cookies = codec
	return nil
}

// Validate checks the configuration.
func (a *App) Validate() error {
	if a.AppID == "" {
		return errors.New("mcauth: app_id is required (https://minecraftauth.me/developers)")
	}
	u, err := url.Parse(a.RedirectURL)
	if err != nil || !u.IsAbs() {
		return fmt.Errorf("mcauth: redirect must be an absolute URL of your mcauth_callback route, got %q", a.RedirectURL)
	}
	if a.SessionKey != "" && len(a.SessionKey) < 32 {
		return errors.New("mcauth: session_key must be at least 32 characters")
	}
	if a.OnAPIError != "deny" && a.OnAPIError != "allow" {
		return fmt.Errorf("mcauth: on_api_error must be deny or allow, got %q", a.OnAPIError)
	}
	for _, s := range a.Scopes {
		if _, ok := mcauth.ParsePlatform(s); !ok {
			return fmt.Errorf("mcauth: unknown scope %q", s)
		}
	}
	if a.LuckPerms != nil {
		u, err := url.Parse(a.LuckPerms.URL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return fmt.Errorf("mcauth: luckperms url must be an http(s) URL of the LuckPerms REST API, got %q", a.LuckPerms.URL)
		}
	}
	for name, realm := range a.Realms {
		if realm == nil || len(realm.Rules) == 0 {
			return fmt.Errorf("mcauth: realm %q has no rules", name)
		}
		for _, rule := range realm.Rules {
			if err := a.validateRule(name, rule); err != nil {
				return err
			}
		}
	}
	return nil
}

// validateDiscordRole accepts `<role id>` or `<guild id> <role id|name>`. A role name always needs its guild: anyone
// can add the MinecraftAuth bot to a server of their own and create a role with the same name.
func validateDiscordRole(values []string) error {
	if len(values) == 2 {
		if !isDiscordID(values[0]) {
			return fmt.Errorf("discord_role guild must be a Discord server ID, not %q", values[0])
		}
		if strings.TrimSpace(values[1]) == "" {
			return fmt.Errorf("discord_role needs a role ID or name")
		}
		return nil
	}
	if !isDiscordID(values[0]) {
		return fmt.Errorf("discord_role %q: a role name needs its guild: discord_role <guild id> %q", values[0], values[0])
	}
	return nil
}

// isDiscordID reports a Discord snowflake, which is all digits.
func isDiscordID(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func (a *App) validateRule(name string, rule Rule) error {
	arity, ok := ruleArity[rule.Type]
	if !ok {
		return fmt.Errorf("mcauth: realm %q: unknown rule %q", name, rule.Type)
	}
	if len(rule.Values) < arity[0] || (arity[1] >= 0 && len(rule.Values) > arity[1]) {
		return fmt.Errorf("mcauth: realm %q: wrong number of values for %s", name, rule.Type)
	}
	if rule.Type == "all" {
		if len(rule.All) == 0 {
			return fmt.Errorf("mcauth: realm %q: all has no rules", name)
		}
		for _, child := range rule.All {
			if err := a.validateRule(name, child); err != nil {
				return err
			}
		}
		return nil
	}
	if len(rule.All) > 0 {
		return fmt.Errorf("mcauth: realm %q: only all can contain rules, not %s", name, rule.Type)
	}
	if rule.Type == "discord_role" {
		if err := validateDiscordRole(rule.Values); err != nil {
			return fmt.Errorf("mcauth: realm %q: %w", name, err)
		}
	}
	if rule.Type == "twitch_subscriber" && len(rule.Values) == 1 && normalizeTier(rule.Values[0]) == "" {
		return fmt.Errorf("mcauth: realm %q: twitch_subscriber tier must be 1, 2 or 3", name)
	}
	if rule.usesLuckPerms() {
		if _, _, err := parseLuckPermsRule(rule); err != nil {
			return fmt.Errorf("mcauth: realm %q: %v", name, err)
		}
		if a.LuckPerms == nil {
			return fmt.Errorf("mcauth: realm %q uses %s, which needs the luckperms option", name, rule.Type)
		}
	}
	if rule.needsServerToken() && a.ServerToken == "" {
		return fmt.Errorf("mcauth: realm %q uses %s, which needs server_token", name, rule.Type)
	}
	return nil
}

// Start implements caddy.App.
func (a *App) Start() error { return nil }

// Stop implements caddy.App.
func (a *App) Stop() error { return nil }

func (a *App) persist() bool { return a.Persist == nil || *a.Persist }

// grantURL builds the MinecraftAuth.me consent page URL for a login attempt.
func (a *App) grantURL(state string) string {
	base := a.APIBase
	if base == "" {
		base = mcauth.DefaultBaseURL
	}
	scopes := append([]string{"minecraft"}, a.Scopes...)
	q := url.Values{
		"app":         {a.AppID},
		"state":       {state},
		"redirectUrl": {a.RedirectURL},
		"scopes":      {strings.Join(scopes, " ")},
	}
	if !a.persist() {
		q.Set("persist", "false")
	}
	return strings.TrimRight(base, "/") + "/grant?" + q.Encode()
}

func normalizeTier(s string) string {
	switch strings.TrimSpace(s) {
	case "1", "1000":
		return "1"
	case "2", "2000":
		return "2"
	case "3", "3000":
		return "3"
	}
	return ""
}

type membershipCache struct {
	ttl     time.Duration
	mu      sync.Mutex
	entries map[string]cacheEntry
}

type cacheEntry struct {
	value   bool
	expires time.Time
}

func newMembershipCache(ttl time.Duration) *membershipCache {
	return &membershipCache{ttl: ttl, entries: map[string]cacheEntry{}}
}

func (c *membershipCache) get(key string) (bool, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[key]
	if !ok || time.Now().After(e.expires) {
		delete(c.entries, key)
		return false, false
	}
	return e.value, true
}

func (c *membershipCache) put(key string, value bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) > 50_000 { // crude bound; entries are tiny and short-lived
		now := time.Now()
		for k, e := range c.entries {
			if now.After(e.expires) {
				delete(c.entries, k)
			}
		}
	}
	c.entries[key] = cacheEntry{value: value, expires: time.Now().Add(c.ttl)}
}

// Interface guards
var (
	_ caddy.App         = (*App)(nil)
	_ caddy.Provisioner = (*App)(nil)
	_ caddy.Validator   = (*App)(nil)
)
