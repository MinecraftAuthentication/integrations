package caddymcauth

import (
	"context"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/caddyserver/caddy/v2"

	mcauth "github.com/MinecraftAuthentication/integrations/lib/go"
)

func TestModulesRegistered(t *testing.T) {
	for _, id := range []string{"mcauth", "http.handlers.mcauth", "http.handlers.mcauth_callback"} {
		info, err := caddy.GetModule(id)
		if err != nil {
			t.Errorf("%s: %v", id, err)
			continue
		}
		if mod := info.New(); mod.CaddyModule().ID.Name() == "" {
			t.Errorf("%s: module has no name", id)
		}
	}
}

func provision(t *testing.T, app *App) *App {
	t.Helper()
	ctx, cancel := caddy.NewContext(caddy.Context{Context: context.Background()})
	t.Cleanup(cancel)
	if err := app.Provision(ctx); err != nil {
		t.Fatal(err)
	}
	return app
}

func TestProvisionDefaults(t *testing.T) {
	app := provision(t, &App{AppID: "a", RedirectURL: "https://x/cb"})
	if time.Duration(app.SessionTTL) != 24*time.Hour || time.Duration(app.MembershipTTL) != 5*time.Minute {
		t.Errorf("ttls: %v %v", app.SessionTTL, app.MembershipTTL)
	}
	if app.OnAPIError != "deny" || app.CookiePrefix != "mcauth" || !app.persist() {
		t.Errorf("defaults: %+v", app)
	}
	if app.client.Credentials != nil {
		t.Error("no server_token should mean anonymous requests")
	}
	if app.client.UserAgent != "MinecraftAuth-Caddy/"+Version || app.keys.Client.UserAgent != app.client.UserAgent {
		t.Errorf("user agent %q", app.client.UserAgent)
	}
	if app.keys.Client.Credentials != nil {
		t.Error("the key fetch shouldn't send the server token")
	}
	if err := app.Validate(); err != nil {
		t.Fatal(err)
	}
	if app.Start() != nil || app.Stop() != nil {
		t.Error("Start/Stop should succeed")
	}
}

func TestProvisionKeepsSettings(t *testing.T) {
	no := false
	app := provision(t, &App{
		AppID: "a", RedirectURL: "https://x/cb", ServerToken: "tok", OnAPIError: "allow", CookiePrefix: "x",
		SessionTTL: caddy.Duration(time.Hour), MembershipTTL: caddy.Duration(time.Second), Persist: &no,
	})
	if time.Duration(app.SessionTTL) != time.Hour || app.cache.ttl != time.Second || app.OnAPIError != "allow" || app.CookiePrefix != "x" || app.persist() {
		t.Errorf("settings overridden: %+v", app)
	}
	if app.client.Credentials != mcauth.ServerToken("tok") {
		t.Errorf("credentials = %#v", app.client.Credentials)
	}
}

func TestProvisionReplacesPlaceholders(t *testing.T) {
	t.Setenv("MCAUTH_TEST_APP_ID", "from-env")
	t.Setenv("MCAUTH_TEST_TOKEN", "env-token")
	t.Setenv("MCAUTH_TEST_KEY", strings.Repeat("k", 32))
	app := provision(t, &App{
		AppID:       "{env.MCAUTH_TEST_APP_ID}",
		ServerToken: "{env.MCAUTH_TEST_TOKEN}",
		RedirectURL: "https://{env.MCAUTH_TEST_APP_ID}.example/cb",
		SessionKey:  "{env.MCAUTH_TEST_KEY}",
	})
	if app.AppID != "from-env" || app.ServerToken != "env-token" || app.RedirectURL != "https://from-env.example/cb" || app.SessionKey != strings.Repeat("k", 32) {
		t.Fatalf("placeholders not replaced: %+v", app)
	}
}

func TestRandomSessionKeyPerStart(t *testing.T) {
	a := provision(t, &App{AppID: "a", RedirectURL: "https://x/cb"})
	b := provision(t, &App{AppID: "a", RedirectURL: "https://x/cb"})
	sealed, err := a.cookies.seal("c", session{UUID: notch})
	if err != nil {
		t.Fatal(err)
	}
	var s session
	if a.cookies.open("c", sealed, &s) != nil || s.UUID != notch {
		t.Fatal("app can't read its own cookie")
	}
	if b.cookies.open("c", sealed, &s) == nil {
		t.Fatal("two generated keys should differ")
	}
}

func TestSessionKeySharedAcrossRestarts(t *testing.T) {
	key := strings.Repeat("s", 32)
	a := provision(t, &App{AppID: "a", RedirectURL: "https://x/cb", SessionKey: key})
	b := provision(t, &App{AppID: "a", RedirectURL: "https://x/cb", SessionKey: key})
	sealed, _ := a.cookies.seal("c", session{UUID: notch})
	var s session
	if err := b.cookies.open("c", sealed, &s); err != nil || s.UUID != notch {
		t.Fatalf("same session_key should read the cookie: %v", err)
	}
}

func TestValidateErrors(t *testing.T) {
	valid := func() *App {
		return &App{AppID: "a", RedirectURL: "https://x/cb", OnAPIError: "deny", ServerToken: "t", Realms: map[string]*Realm{}}
	}
	for name, tc := range map[string]struct {
		mutate func(*App)
		want   string
	}{
		"no app_id":         {func(a *App) { a.AppID = "" }, "app_id"},
		"no redirect":       {func(a *App) { a.RedirectURL = "" }, "redirect"},
		"relative redirect": {func(a *App) { a.RedirectURL = "/cb" }, "redirect"},
		"bad redirect":      {func(a *App) { a.RedirectURL = "https://x/%zz" }, "redirect"},
		"short session_key": {func(a *App) { a.SessionKey = "short" }, "32 characters"},
		"bad on_api_error":  {func(a *App) { a.OnAPIError = "maybe" }, "on_api_error"},
		"unknown scope":     {func(a *App) { a.Scopes = []string{"discord", "myspace"} }, "myspace"},
		"nil realm":         {func(a *App) { a.Realms["r"] = nil }, "no rules"},
		"empty realm":       {func(a *App) { a.Realms["r"] = &Realm{} }, "no rules"},
		"unknown rule":      {func(a *App) { a.Realms["r"] = &Realm{Rules: []Rule{{Type: "steam_friend"}}} }, "unknown rule"},
		"linked with value": {func(a *App) { a.Realms["r"] = &Realm{Rules: []Rule{{Type: "linked", Values: []string{"x"}}}} }, "number of values"},
		"two discord servers": {func(a *App) {
			a.Realms["r"] = &Realm{Rules: []Rule{{Type: "discord_server", Values: []string{"1", "2"}}}}
		}, "number of values"},
		"discord_role without role": {func(a *App) { a.Realms["r"] = &Realm{Rules: []Rule{{Type: "discord_role"}}} }, "number of values"},
		"discord_role name without guild": {func(a *App) {
			a.Realms["r"] = &Realm{Rules: []Rule{{Type: "discord_role", Values: []string{"Moderator"}}}}
		}, "needs its guild"},
		"discord_role bad guild": {func(a *App) {
			a.Realms["r"] = &Realm{Rules: []Rule{{Type: "discord_role", Values: []string{"my-server", "Moderator"}}}}
		}, "Discord server ID"},
		"discord_role three values": {func(a *App) {
			a.Realms["r"] = &Realm{Rules: []Rule{{Type: "discord_role", Values: []string{"1", "2", "3"}}}}
		}, "number of values"},
		"twitch tier 4": {func(a *App) {
			a.Realms["r"] = &Realm{Rules: []Rule{{Type: "twitch_subscriber", Values: []string{"4"}}}}
		}, "tier"},
		"membership without token": {func(a *App) {
			a.ServerToken = ""
			a.Realms["r"] = &Realm{Rules: []Rule{{Type: "player", Values: []string{"Notch"}}, {Type: "youtube_member"}}}
		}, "server_token"},
	} {
		app := valid()
		tc.mutate(app)
		if err := app.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: want error containing %q, got %v", name, tc.want, err)
		}
	}
}

func TestValidateAcceptsEveryRule(t *testing.T) {
	app := &App{
		AppID: "a", RedirectURL: "https://x/cb", OnAPIError: "allow", ServerToken: "t",
		SessionKey: strings.Repeat("k", 32), Scopes: []string{"discord", "YouTube"},
		Realms: map[string]*Realm{"all": {Rules: []Rule{
			{Type: "player", Values: []string{notch, "jeb_", "Dinnerbone"}},
			{Type: "linked"},
			{Type: "discord_server", Values: []string{"1"}},
			{Type: "discord_role", Values: []string{"1", "Moderator"}},
			{Type: "twitch_follower"},
			{Type: "twitch_subscriber"},
			{Type: "twitch_subscriber", Values: []string{"3000"}},
			{Type: "patreon_member"},
			{Type: "patreon_member", Values: []string{"Gold Tier"}},
			{Type: "youtube_subscriber"},
			{Type: "youtube_member"},
		}}},
	}
	if err := app.Validate(); err != nil {
		t.Fatal(err)
	}
	// local-only realms don't need a server token
	app.ServerToken = ""
	app.Realms = map[string]*Realm{"local": {Rules: []Rule{{Type: "player", Values: []string{"Notch"}}, {Type: "linked"}}}}
	if err := app.Validate(); err != nil {
		t.Fatal(err)
	}
}

func TestGrantURL(t *testing.T) {
	no := false
	for name, tc := range map[string]struct {
		app  App
		base string
		want url.Values
	}{
		"defaults": {
			App{AppID: "app", RedirectURL: "https://x/cb"},
			"https://minecraftauth.me/grant",
			url.Values{"app": {"app"}, "state": {"S"}, "redirectUrl": {"https://x/cb"}, "scopes": {"minecraft"}},
		},
		"scopes, no persist, custom base": {
			App{AppID: "app", RedirectURL: "https://x/cb", Scopes: []string{"discord", "twitch"}, Persist: &no, APIBase: "http://localhost:8080/"},
			"http://localhost:8080/grant",
			url.Values{"app": {"app"}, "state": {"S"}, "redirectUrl": {"https://x/cb"}, "scopes": {"minecraft discord twitch"}, "persist": {"false"}},
		},
	} {
		u, err := url.Parse(tc.app.grantURL("S"))
		if err != nil {
			t.Fatal(err)
		}
		if got := u.Scheme + "://" + u.Host + u.Path; got != tc.base {
			t.Errorf("%s: base %q, want %q", name, got, tc.base)
		}
		if u.Query().Encode() != tc.want.Encode() {
			t.Errorf("%s: query %q, want %q", name, u.Query().Encode(), tc.want.Encode())
		}
	}
}

func TestGrantURLDoesNotMutateScopes(t *testing.T) {
	scopes := make([]string, 1, 4)
	scopes[0] = "discord"
	app := &App{AppID: "a", RedirectURL: "https://x/cb", Scopes: scopes}
	app.grantURL("s")
	app.grantURL("s")
	if len(app.Scopes) != 1 || app.Scopes[0] != "discord" {
		t.Fatalf("scopes changed: %v", app.Scopes)
	}
}

func TestNormalizeTier(t *testing.T) {
	for in, want := range map[string]string{
		"1": "1", "1000": "1", " 2 ": "2", "2000": "2", "3": "3", "3000": "3",
		"": "", "0": "", "4": "", "Prime": "", "tier1": "",
	} {
		if got := normalizeTier(in); got != want {
			t.Errorf("normalizeTier(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMembershipCache(t *testing.T) {
	c := newMembershipCache(time.Hour)
	if _, ok := c.get("k"); ok {
		t.Fatal("empty cache hit")
	}
	c.put("yes", true)
	c.put("no", false)
	if v, ok := c.get("yes"); !ok || !v {
		t.Error("cached true lost")
	}
	if v, ok := c.get("no"); !ok || v {
		t.Error("cached false lost")
	}

	expired := newMembershipCache(-time.Second)
	expired.put("k", true)
	if _, ok := expired.get("k"); ok {
		t.Error("expired entry returned")
	}
	if len(expired.entries) != 0 {
		t.Error("expired entry not dropped on read")
	}
}

func TestMembershipCacheEvictsExpiredWhenFull(t *testing.T) {
	c := newMembershipCache(time.Hour)
	past := time.Now().Add(-time.Minute)
	for i := range 50_001 {
		c.entries["old"+strconv.Itoa(i)] = cacheEntry{expires: past}
	}
	c.entries["live"] = cacheEntry{value: true, expires: time.Now().Add(time.Hour)}
	c.put("new", true)
	if len(c.entries) != 2 {
		t.Fatalf("want only live entries left, got %d", len(c.entries))
	}
	if _, ok := c.get("live"); !ok {
		t.Error("unexpired entry evicted")
	}
}
